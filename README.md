# books-server

A REST server for managing a book collection, written in Java 17 with the built-in `HttpServer` and Gson. Books are stored in memory, every request is logged with a request ID, and the log level can be changed at runtime. Built for the Kaplat course.

**Stack:** Java 17 · `com.sun.net.httpserver` · Gson · Docker

## Run

```bash
docker build -t books-server .
docker run --rm -p 8574:8574 books-server
```

Check that it's up:

```bash
curl localhost:8574/books/health
```
