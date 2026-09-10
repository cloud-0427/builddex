package lows.dgeon.ightr.jiagu;

import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Uses the installed payload's classes, not duplicate coroutine classes from the test APK. */
public class CoroutineServicesTest {
    static android.app.Instrumentation platformRunner;

    private android.app.Instrumentation instrumentation() {
        return platformRunner != null ? platformRunner : InstrumentationRegistry.getInstrumentation();
    }

    private ClassLoader loader() {
        return instrumentation().getTargetContext().getClassLoader();
    }

    @Test public void discoversAndInstantiatesBothServices() throws Exception {
        verifyService("kotlinx.coroutines.internal.MainDispatcherFactory",
                "kotlinx.coroutines.android.AndroidDispatcherFactory");
        verifyService("kotlinx.coroutines.CoroutineExceptionHandler",
                "kotlinx.coroutines.android.AndroidExceptionPreHandler");
    }

    private void verifyService(String service, String provider) throws Exception {
        Class<?> spi = loader().loadClass(service);
        Set<String> found = new HashSet<>();
        for (Object instance : ServiceLoader.load(spi, loader())) {
            assertTrue(spi.isInstance(instance));
            found.add(instance.getClass().getName());
        }
        assertTrue("Missing provider for " + service + ": " + found, found.contains(provider));
    }

    @Test public void lifecycleScopeDispatchersMainRunsOnMainLooper() throws Exception {
        AtomicReference<Object> dispatcher = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation().runOnMainSync(() -> {
            try {
                Class<?> ownerType = loader().loadClass("androidx.lifecycle.LifecycleOwner");
                Class<?> lifecycleType = loader().loadClass("androidx.lifecycle.Lifecycle");
                AtomicReference<Object> lifecycle = new AtomicReference<>();
                Object owner = Proxy.newProxyInstance(loader(), new Class<?>[]{ownerType},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getLifecycle")) return lifecycle.get();
                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                            return "ServiceVerificationOwner";
                        });
                lifecycle.set(loader().loadClass("androidx.lifecycle.LifecycleRegistry")
                        .getConstructor(ownerType).newInstance(owner));
                // This production API uses Dispatchers.Main.immediate internally. Do not
                // add test-only keep rules for Dispatchers or its inlined getMain method.
                Object scope = loader().loadClass("androidx.lifecycle.LifecycleKt")
                        .getMethod("getCoroutineScope", lifecycleType).invoke(null, lifecycle.get());
                Object context = scope.getClass().getMethod("getCoroutineContext").invoke(scope);
                Class<?> interceptor = loader().loadClass("kotlin.coroutines.ContinuationInterceptor");
                Object key = interceptor.getField("Key").get(null);
                Class<?> keyType = loader().loadClass("kotlin.coroutines.CoroutineContext$Key");
                dispatcher.set(context.getClass().getMethod("get", keyType).invoke(context, key));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError("Main dispatcher initialization failed", failure.get());
        assertNotNull(dispatcher.get());
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Looper> executedOn = new AtomicReference<>();
        Class<?> contextType = loader().loadClass("kotlin.coroutines.CoroutineContext");
        Method dispatch = dispatcher.get().getClass().getMethod("dispatch", contextType, Runnable.class);
        dispatch.setAccessible(true);
        dispatch.invoke(dispatcher.get(), dispatcher.get(), (Runnable) () -> {
            executedOn.set(Looper.myLooper());
            ran.countDown();
        });
        assertTrue("Dispatchers.Main did not run", ran.await(10, TimeUnit.SECONDS));
        assertSame(Looper.getMainLooper(), executedOn.get());
    }
}
