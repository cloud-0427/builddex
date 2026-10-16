package io.github.xjc.jiagu.local;

import android.app.*;
import android.os.Bundle;
import android.database.Cursor;
import java.io.*;
import java.util.UUID;

/** Test APK only: kill this process at committed journal and SQLite boundaries. */
public final class LocalRecoveryInstrumentation extends Instrumentation {
    private Bundle args;
    public void onCreate(Bundle args) { super.onCreate(args); this.args=args; start(); }
    public void onStart() {
        Bundle result=new Bundle();
        try {
            String session=args.getString("session");
            String phase=args.getString("phase");
            LocalStartupStage stage=LocalStartupStage.fromWireName(args.getString("stage"));
            File directory=new File(getTargetContext().getNoBackupFilesDir(),"p0-process-boundaries");
            if ("write".equals(args.getString("action"))) {
                LocalEventJournal journal=new LocalEventJournal(directory,session);
                LocalStartupEvent entered=event(session,session+"-enter",stage,LocalStartupStatus.STARTED,1);
                entered.recordType="CHECKPOINT"; entered.checkpoint="ENTER";
                journal.append(entered.toJson().toString());
                if (phase.equals("terminal")) journal.append(event(session,session+"-terminal",stage,LocalStartupStatus.SUCCEEDED,2).toJson().toString());
                if (phase.equals("half")) {
                    try(FileOutputStream out=new FileOutputStream(journal.file,true)) { out.write(new byte[]{0x4a,0x47,0x53}); out.getFD().sync(); }
                }
                if (phase.equals("transaction")) {
                    journal.close();
                    LocalEventStore store=new LocalEventStore(directory);
                    store.db.beginTransaction();
                    store.insert(event(session,session+"-uncommitted",stage,LocalStartupStatus.SUCCEEDED,2));
                    // Deliberately die before setTransactionSuccessful/endTransaction.
                }
                if (phase.equals("lease")) {
                    LocalEventStore store=new LocalEventStore(directory);
                    store.insert(event(session,session+"-leased",stage,LocalStartupStatus.SUCCEEDED,2));
                    if (store.claim(System.currentTimeMillis())==null) throw new AssertionError("claim missing");
                }
                result.putString("stream","SYNCED "+session+" "+phase+"\n"); sendStatus(0,result);
                android.os.Process.killProcess(android.os.Process.myPid());
                return;
            }
            try(LocalEventStore store=new LocalEventStore(directory)) {
                LocalStartupEvent discovery=event(UUID.randomUUID().toString(),UUID.randomUUID().toString(),stage,LocalStartupStatus.SUCCEEDED,1);
                store.importJournals(discovery);
                require(store,"SELECT count(*) FROM records WHERE id='"+session+"-enter'",1);
                require(store,"SELECT count(*) FROM records WHERE id='"+session+"-uncommitted'",0);
                require(store,"SELECT count(*) FROM records WHERE id='"+session+"-terminal'",phase.equals("terminal")?1:0);
                require(store,"SELECT count(*) FROM records WHERE id='recovery-"+session+"'",1);
                result.putString("stream","PASS process-death recovery "+session+" "+phase+"\n");
                finish(Activity.RESULT_OK,result);
            }
        } catch(Throwable failure) { result.putString("stream",android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED,result); }
    }
    private LocalStartupEvent event(String session,String id,LocalStartupStage stage,LocalStartupStatus status,long seq) {
        LocalStartupEvent event=new LocalStartupEvent(session,id,stage,status,LocalStartupResultCode.NONE,System.currentTimeMillis(),1,0,"test.app",1,"test.app");
        event.sequence=seq; return event;
    }
    private void require(LocalEventStore store,String sql,int expected) {
        try(Cursor c=store.db.rawQuery(sql,null)) { c.moveToFirst(); if(c.getInt(0)!=expected) throw new AssertionError(sql+" got "+c.getInt(0)); }
    }
}
