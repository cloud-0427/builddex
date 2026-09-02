package httpapi

import (
	"embed"
	"io/fs"
	"net/http"
)

//go:embed admin/*
var adminFiles embed.FS

func (a *API) routesAdmin() {
	assets, err := fs.Sub(adminFiles, "admin")
	if err != nil {
		panic(err)
	}
	a.mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		http.Redirect(w, r, "/admin/", http.StatusTemporaryRedirect)
	})
	a.mux.HandleFunc("GET /admin", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/admin/", http.StatusTemporaryRedirect)
	})
	adminHandler := http.StripPrefix("/admin/", http.FileServer(http.FS(assets)))
	a.mux.Handle("GET /admin/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Asset names are not content-hashed. Revalidate them after every server
		// deployment so an old page asset cannot remain in the browser cache.
		w.Header().Set("Cache-Control", "no-store")
		adminHandler.ServeHTTP(w, r)
	}))
}
