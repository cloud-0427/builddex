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
	a.mux.Handle("GET /admin/", http.StripPrefix("/admin/", http.FileServer(http.FS(assets))))
}
