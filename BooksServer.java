import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.logging.Formatter;
import java.util.stream.*;

public class BooksServer {

    // ======================== BOOK MODEL ========================

    static class Book {
        int id;
        String title;
        String author;
        int year;
        int price;
        List<String> genres;

        Book(int id, String title, String author, int year, int price, List<String> genres) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.year = year;
            this.price = price;
            this.genres = new ArrayList<>(genres);
        }
    }

    // ======================== LOG FORMATTER ========================

    static class BooksLogFormatter extends Formatter {
        private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record) {
            LocalDateTime dt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(record.getMillis()), ZoneId.systemDefault());
            Level l = record.getLevel();
            String levelStr;
            if (l == Level.FINE)   levelStr = "DEBUG";
            else if (l == Level.INFO)   levelStr = "INFO";
            else if (l == Level.SEVERE) levelStr = "ERROR";
            else                        levelStr = l.getName();
            return dt.format(DTF) + " " + levelStr + ": " + record.getMessage() + "\n";
        }
    }

    // ======================== SHARED STATE ========================

    private static final List<Book> books = new ArrayList<>();
    private static int nextId = 1;
    private static int requestCounter = 0;

    private static final Set<String> VALID_GENRES = new HashSet<>(Arrays.asList(
        "SCI_FI", "NOVEL", "HISTORY", "MANGA", "ROMANCE", "PROFESSIONAL"
    ));
    private static final Gson gson = new Gson();

    private static Logger requestLogger;
    private static Logger booksLogger;

    // ======================== LOGGING SETUP ========================

    static {
        try {
            new File("logs").mkdirs();
            BooksLogFormatter fmt = new BooksLogFormatter();

            // --- request-logger: file + console ---
            requestLogger = Logger.getLogger("request-logger");
            requestLogger.setUseParentHandlers(false);
            requestLogger.setLevel(Level.INFO);

            FileHandler rfh = new FileHandler("logs/requests.log", true) {
                @Override public synchronized void publish(LogRecord r) { super.publish(r); flush(); }
            };
            rfh.setFormatter(fmt);
            rfh.setLevel(Level.ALL);
            requestLogger.addHandler(rfh);

            StreamHandler stdout = new StreamHandler(System.out, fmt) {
                @Override public synchronized void publish(LogRecord r) { super.publish(r); flush(); }
            };
            stdout.setLevel(Level.ALL);
            requestLogger.addHandler(stdout);

            // --- books-logger: file only ---
            booksLogger = Logger.getLogger("books-logger");
            booksLogger.setUseParentHandlers(false);
            booksLogger.setLevel(Level.INFO);

            FileHandler bfh = new FileHandler("logs/books.log", true) {
                @Override public synchronized void publish(LogRecord r) { super.publish(r); flush(); }
            };
            bfh.setFormatter(fmt);
            bfh.setLevel(Level.ALL);
            booksLogger.addHandler(bfh);

        } catch (Exception e) {
            throw new RuntimeException("Logging setup failed: " + e.getMessage(), e);
        }
    }

    private static void rlog(Level l, String msg, int req) {
        if (requestLogger.isLoggable(l))
            requestLogger.log(l, msg + " | request #" + req);
    }

    private static void blog(Level l, String msg, int req) {
        if (booksLogger.isLoggable(l))
            booksLogger.log(l, msg + " | request #" + req);
    }

    // ======================== MAIN ========================

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8574), 0);
        server.createContext("/books/health", BooksServer::handleHealth);
        server.createContext("/books/total",  BooksServer::handleBooksTotal);
        server.createContext("/books",        BooksServer::handleBooksGet);
        server.createContext("/book",         BooksServer::handleBook);
        server.createContext("/logs/level",   BooksServer::handleLogsLevel);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        System.out.println("Server started on port 8574");
    }

    // ======================== /books/health ========================

    private static void handleHealth(HttpExchange ex) throws IOException {
        int req = ++requestCounter;
        long t0 = System.currentTimeMillis();
        String method = ex.getRequestMethod();
        rlog(Level.INFO,
            "Incoming request | #" + req + " | resource: /books/health | HTTP Verb " + method, req);
        if ("GET".equals(method)) {
            send(ex, 200, "OK");
        } else {
            send(ex, 405, "Method Not Allowed");
        }
        rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
    }

    // ======================== /book dispatcher ========================

    private static void handleBook(HttpExchange ex) throws IOException {
        int req = ++requestCounter;
        long t0 = System.currentTimeMillis();
        String method = ex.getRequestMethod();
        rlog(Level.INFO,
            "Incoming request | #" + req + " | resource: /book | HTTP Verb " + method, req);
        try {
            switch (method) {
                case "POST":   bookCreate(ex, req); break;
                case "GET":    bookGet(ex, req);    break;
                case "PUT":    bookUpdate(ex, req); break;
                case "DELETE": bookDelete(ex, req); break;
                default: send(ex, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            send(ex, 500, "Internal error: " + e.getMessage());
        }
        rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
    }

    // ---- POST /book ----

    private static void bookCreate(HttpExchange ex, int req) throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(readBody(ex)).getAsJsonObject();
        } catch (Exception e) {
            send(ex, 400, "Invalid JSON body");
            return;
        }

        String title, author;
        int year, price;
        List<String> genres = new ArrayList<>();
        try {
            title  = body.get("title").getAsString();
            author = body.get("author").getAsString();
            year   = body.get("year").getAsInt();
            price  = body.get("price").getAsInt();
            body.get("genres").getAsJsonArray().forEach(e -> genres.add(e.getAsString()));
        } catch (Exception e) {
            send(ex, 400, "Missing or invalid fields in request body");
            return;
        }

        // Validate — no INFO/DEBUG on error, only ERROR
        for (Book b : books) {
            if (b.title.equalsIgnoreCase(title)) {
                String err = "Error: Book with the title [" + title + "] already exists in the system";
                blog(Level.SEVERE, err, req);
                sendJson(ex, 409, null, err);
                return;
            }
        }
        if (year < 1940 || year > 2100) {
            String err = "Error: Can't create new Book that its year [" + year +
                         "] is not in the accepted range [1940 -> 2100]";
            blog(Level.SEVERE, err, req);
            sendJson(ex, 409, null, err);
            return;
        }
        if (price <= 0) {
            String err = "Error: Can't create new Book with negative price";
            blog(Level.SEVERE, err, req);
            sendJson(ex, 409, null, err);
            return;
        }

        // Success — INFO then DEBUG
        blog(Level.INFO, "Creating new Book with Title [" + title + "]", req);
        int id = nextId++;
        blog(Level.FINE,
            "Currently there are " + books.size() +
            " Books in the system. New Book will be assigned with id " + id, req);
        books.add(new Book(id, title, author, year, price, genres));
        sendJson(ex, 200, id, null);
    }

    // ---- GET /book ----

    private static void bookGet(HttpExchange ex, int req) throws IOException {
        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        int id;
        try {
            id = Integer.parseInt(params.get("id"));
        } catch (Exception e) {
            send(ex, 400, "Missing or invalid id parameter");
            return;
        }

        Book book = findById(id);
        if (book == null) {
            String err = "Error: no such Book with id " + id;
            blog(Level.SEVERE, err, req);
            sendJson(ex, 404, null, err);
            return;
        }

        // Success — DEBUG only
        blog(Level.FINE, "Fetching book id " + id + " details", req);
        sendJson(ex, 200, bookToJson(book), null);
    }

    // ---- PUT /book ----

    private static void bookUpdate(HttpExchange ex, int req) throws IOException {
        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        int id, newPrice;
        try {
            id = Integer.parseInt(params.get("id"));
        } catch (Exception e) {
            send(ex, 400, "Missing or invalid id parameter");
            return;
        }
        try {
            newPrice = Integer.parseInt(params.get("price"));
        } catch (Exception e) {
            send(ex, 400, "Missing or invalid price parameter");
            return;
        }

        Book book = findById(id);
        if (book == null) {
            String err = "Error: no such Book with id " + id;
            blog(Level.SEVERE, err, req);
            sendJson(ex, 404, null, err);
            return;
        }
        if (newPrice <= 0) {
            String err = "Error: price update for Book [" + id + "] must be a positive integer";
            blog(Level.SEVERE, err, req);
            sendJson(ex, 409, null, err);
            return;
        }

        // Success — INFO then DEBUG
        blog(Level.INFO, "Update Book id [" + id + "] price to " + newPrice, req);
        int oldPrice = book.price;
        blog(Level.FINE,
            "Book [" + book.title + "] price change: " + oldPrice + " --> " + newPrice, req);
        book.price = newPrice;
        sendJson(ex, 200, oldPrice, null);
    }

    // ---- DELETE /book ----

    private static void bookDelete(HttpExchange ex, int req) throws IOException {
        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        int id;
        try {
            id = Integer.parseInt(params.get("id"));
        } catch (Exception e) {
            send(ex, 400, "Missing or invalid id parameter");
            return;
        }

        Book book = findById(id);
        if (book == null) {
            String err = "Error: no such book with id " + id;
            blog(Level.SEVERE, err, req);
            sendJson(ex, 404, null, err);
            return;
        }

        // Success — INFO then DEBUG
        blog(Level.INFO, "Removing book [" + book.title + "]", req);
        books.remove(book);
        blog(Level.FINE,
            "After removing book [" + book.title + "] id: [" + id +
            "] there are " + books.size() + " books in the system", req);
        sendJson(ex, 200, books.size(), null);
    }

    // ======================== GET /books ========================

    private static void handleBooksGet(HttpExchange ex) throws IOException {
        int req = ++requestCounter;
        long t0 = System.currentTimeMillis();
        String method = ex.getRequestMethod();
        rlog(Level.INFO,
            "Incoming request | #" + req + " | resource: /books | HTTP Verb " + method, req);

        if (!"GET".equals(method)) {
            send(ex, 405, "Method Not Allowed");
            rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
            return;
        }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        try {
            validateFilterParams(params);
        } catch (IllegalArgumentException e) {
            // 400 = no logs at all
            send(ex, 400, e.getMessage());
            rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
            return;
        }

        List<Book> filtered = filterBooks(params);
        filtered.sort((a, b) -> a.title.compareToIgnoreCase(b.title));

        blog(Level.INFO, "Total Books found for requested filters is " + filtered.size(), req);

        JsonArray arr = new JsonArray();
        filtered.forEach(b -> arr.add(bookToJson(b)));
        sendJson(ex, 200, arr, null);

        rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
    }

    // ======================== GET /books/total ========================

    private static void handleBooksTotal(HttpExchange ex) throws IOException {
        int req = ++requestCounter;
        long t0 = System.currentTimeMillis();
        String method = ex.getRequestMethod();
        rlog(Level.INFO,
            "Incoming request | #" + req + " | resource: /books/total | HTTP Verb " + method, req);

        if (!"GET".equals(method)) {
            send(ex, 405, "Method Not Allowed");
            rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
            return;
        }

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());
        try {
            validateFilterParams(params);
        } catch (IllegalArgumentException e) {
            send(ex, 400, e.getMessage());
            rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
            return;
        }

        List<Book> filtered = filterBooks(params);

        blog(Level.INFO, "Total Books found for requested filters is " + filtered.size(), req);

        sendJson(ex, 200, filtered.size(), null);

        rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
    }

    // ======================== /logs/level ========================

    private static void handleLogsLevel(HttpExchange ex) throws IOException {
        int req = ++requestCounter;
        long t0 = System.currentTimeMillis();
        String method = ex.getRequestMethod();
        rlog(Level.INFO,
            "Incoming request | #" + req + " | resource: /logs/level | HTTP Verb " + method, req);

        Map<String, String> params = parseQuery(ex.getRequestURI().getQuery());

        if ("GET".equals(method)) {
            Logger logger = loggerByName(params.get("logger-name"));
            if (logger == null) {
                send(ex, 400, "Unknown logger name: " + params.get("logger-name"));
            } else {
                send(ex, 200, levelToStr(logger.getLevel()));
            }
        } else if ("PUT".equals(method)) {
            Logger logger = loggerByName(params.get("logger-name"));
            Level newLevel = strToLevel(params.get("logger-level"));
            if (logger == null) {
                send(ex, 400, "Unknown logger name: " + params.get("logger-name"));
            } else if (newLevel == null) {
                send(ex, 400, "Unknown logger level: " + params.get("logger-level"));
            } else {
                logger.setLevel(newLevel);
                send(ex, 200, levelToStr(logger.getLevel()));
            }
        } else {
            send(ex, 405, "Method Not Allowed");
        }

        rlog(Level.FINE, "request #" + req + " duration: " + (System.currentTimeMillis() - t0) + "ms", req);
    }

    // ======================== HELPERS ========================

    private static void validateFilterParams(Map<String, String> params) {
        String[] numericKeys = {"price-bigger-than", "price-less-than", "year-bigger-than", "year-less-than"};
        for (String key : numericKeys) {
            if (params.containsKey(key)) {
                try {
                    Integer.parseInt(params.get(key));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid number for parameter: " + key);
                }
            }
        }
        if (params.containsKey("genres")) {
            String[] gs = params.get("genres").split(",");
            for (String g : gs) {
                if (!VALID_GENRES.contains(g.trim())) {
                    throw new IllegalArgumentException("Invalid genre: " + g.trim());
                }
            }
        }
    }

    private static List<Book> filterBooks(Map<String, String> params) {
        List<Book> result = new ArrayList<>(books);
        if (params.containsKey("author")) {
            String a = params.get("author").toLowerCase();
            result = result.stream()
                .filter(b -> b.author.toLowerCase().equals(a))
                .collect(Collectors.toList());
        }
        if (params.containsKey("price-bigger-than")) {
            int v = Integer.parseInt(params.get("price-bigger-than"));
            result = result.stream().filter(b -> b.price >= v).collect(Collectors.toList());
        }
        if (params.containsKey("price-less-than")) {
            int v = Integer.parseInt(params.get("price-less-than"));
            result = result.stream().filter(b -> b.price <= v).collect(Collectors.toList());
        }
        if (params.containsKey("year-bigger-than")) {
            int v = Integer.parseInt(params.get("year-bigger-than"));
            result = result.stream().filter(b -> b.year >= v).collect(Collectors.toList());
        }
        if (params.containsKey("year-less-than")) {
            int v = Integer.parseInt(params.get("year-less-than"));
            result = result.stream().filter(b -> b.year <= v).collect(Collectors.toList());
        }
        if (params.containsKey("genres")) {
            List<String> gs = Arrays.stream(params.get("genres").split(","))
                .map(String::trim).collect(Collectors.toList());
            result = result.stream()
                .filter(b -> b.genres.stream().anyMatch(gs::contains))
                .collect(Collectors.toList());
        }
        return result;
    }

    private static Book findById(int id) {
        return books.stream().filter(b -> b.id == id).findFirst().orElse(null);
    }

    private static JsonObject bookToJson(Book b) {
        JsonObject o = new JsonObject();
        o.addProperty("id", b.id);
        o.addProperty("title", b.title);
        o.addProperty("author", b.author);
        o.addProperty("price", b.price);
        o.addProperty("year", b.year);
        JsonArray genres = new JsonArray();
        b.genres.forEach(genres::add);
        o.add("genres", genres);
        return o;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            try {
                String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                map.put(k, v);
            } catch (Exception ignored) {}
        }
        return map;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static void sendJson(HttpExchange ex, int code, Object result, String err) throws IOException {
        JsonObject resp = new JsonObject();
        if (result != null) {
            if (result instanceof Integer)     resp.addProperty("result", (Integer) result);
            else if (result instanceof String) resp.addProperty("result", (String) result);
            else if (result instanceof JsonElement) resp.add("result", (JsonElement) result);
        }
        if (err != null) resp.addProperty("errorMessage", err);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        send(ex, code, gson.toJson(resp));
    }

    private static Logger loggerByName(String name) {
        if ("request-logger".equals(name)) return requestLogger;
        if ("books-logger".equals(name))   return booksLogger;
        return null;
    }

    private static String levelToStr(Level l) {
        if (l == Level.FINE)   return "DEBUG";
        if (l == Level.INFO)   return "INFO";
        if (l == Level.SEVERE) return "ERROR";
        return l.getName();
    }

    private static Level strToLevel(String s) {
        if (s == null) return null;
        switch (s.toUpperCase()) {
            case "DEBUG": return Level.FINE;
            case "INFO":  return Level.INFO;
            case "ERROR": return Level.SEVERE;
            default:      return null;
        }
    }
}
