#!/bin/sh

CGO_ENABLED=0 \
GOOS=linux \
GOARCH=amd64 \
GOAMD64=v3 \
go build \
    -trimpath \
    -ldflags="-s -w" \
    -o jiagu-server \
    cmd/jiagu-server/main.go
