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

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/books/health` | Health check, returns `OK` |
| GET | `/books/total` | Number of books, as `{"result": n}` |
| GET | `/books` | List of books, as `{"result": [...]}` |
| POST | `/book` | Create a book, returns `{"result": id}` |

### Creating a book

```bash
curl -X POST localhost:8574/book -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","year":1965,"price":50,"genres":["sci-fi"]}'
```

Validation errors return `409` with `{"errorMessage": "..."}`:
- a book with the same title already exists (case-insensitive)
- year outside 1940-2100
- price of 0 or less

Invalid JSON or missing fields return `400`.

### Reading, updating and deleting a book

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/book?id=1` | `200`, the book object | `404` unknown id, `400` bad id |
| PUT | `/book?id=1&price=60` | `200`, the **old** price | `404` unknown id, `409` price ≤ 0, `400` bad params |
| DELETE | `/book?id=1` | `200`, number of books left | `404` unknown id, `400` bad id |

Example response of `GET /book?id=1`:

```json
{"result":{"id":1,"title":"Dune","author":"Frank Herbert","price":50,"year":1965,"genres":["sci-fi"]}}
```

## Filtering

`GET /books` and `GET /books/total` accept the same optional filters, combined with AND. `/books` returns the matching books sorted by title; `/books/total` returns only the count.

| Parameter | Meaning |
|---|---|
| `author` | exact match, case-insensitive |
| `price-bigger-than` / `price-less-than` | inclusive bounds, whole numbers |
| `year-bigger-than` / `year-less-than` | inclusive bounds, whole numbers |
| `genres` | comma-separated, matches books having any of them; only genres from the server's built-in list are accepted |

```bash
curl "localhost:8574/books?price-bigger-than=50&author=frank%20herbert"
```

Invalid numbers or unknown genres return `400`.

## Logging

Two loggers, `request-logger` and `books-logger`, with levels `ERROR`, `INFO` and `DEBUG`. Book events are written to `logs/books.log`. The level can be changed while the server is running:

```bash
curl "localhost:8574/logs/level?logger-name=books-logger"
curl -X PUT "localhost:8574/logs/level?logger-name=books-logger&logger-level=DEBUG"
```
