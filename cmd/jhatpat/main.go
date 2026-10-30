// Command jhatpat runs the JhatPat LAN file transfer server.
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"

	"jhatpat/internal/server"
)

func main() {
	port := flag.Int("port", 8787, "port to listen on")
	flag.Parse()

	s, err := server.New(server.Config{Port: *port})
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("JhatPat listening on :%d\n", *port)
	log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", *port), s.Handler()))
}
