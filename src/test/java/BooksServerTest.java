import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests: each one talks to the real server over HTTP on a free port.
 */
public class BooksServerTest {

    private static HttpServer server;
    private static HttpClient client;
    private static String base;

    @BeforeClass
    public static void startServer() throws IOException {
        server = BooksServer.start(0);
        base = "http://localhost:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterClass
    public static void stopServer() {
        BooksServer.stop(server);
    }

    @Before
    @After
    public void cleanState() {
        BooksServer.reset();
    }

    // ---------- helpers ----------

    private static class Res {
        final int status;
        final String body;
        Res(int status, String body) { this.status = status; this.body = body; }
        JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
        JsonElement result() { return json().get("result"); }
        String error() { return json().get("errorMessage").getAsString(); }
    }

    private static Res send(String method, String path, String jsonBody) throws Exception {
        HttpRequest.BodyPublisher body = jsonBody == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .method(method, body)
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new Res(resp.statusCode(), resp.body());
    }

    private static Res get(String path)    throws Exception { return send("GET", path, null); }
    private static Res put(String path)    throws Exception { return send("PUT", path, null); }
    private static Res delete(String path) throws Exception { return send("DELETE", path, null); }

    private static String bookJson(String title, String author, int year, int price, String... genres) {
        StringBuilder g = new StringBuilder();
        for (String s : genres) {
            if (g.length() > 0) g.append(',');
            g.append('"').append(s).append('"');
        }
        return "{\"title\":\"" + title + "\",\"author\":\"" + author + "\",\"year\":" + year
            + ",\"price\":" + price + ",\"genres\":[" + g + "]}";
    }

    private static int create(String title, String author, int year, int price, String... genres) throws Exception {
        Res r = send("POST", "/book", bookJson(title, author, year, price, genres));
        assertEquals(200, r.status);
        return r.result().getAsInt();
    }

    private static List<String> titles(Res r) {
        List<String> out = new ArrayList<>();
        JsonArray arr = r.result().getAsJsonArray();
        arr.forEach(e -> out.add(e.getAsJsonObject().get("title").getAsString()));
        return out;
    }

    /** A small mixed catalogue used by the filtering tests. */
    private static void seedCatalogue() throws Exception {
        create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        create("Neuromancer", "William Gibson", 1984, 40, "SCI_FI", "NOVEL");
        create("Berserk", "Kentaro Miura", 1990, 30, "MANGA");
        create("Sapiens", "Yuval Harari", 2011, 70, "HISTORY");
        create("Clean Code", "Robert Martin", 2008, 60, "PROFESSIONAL");
    }

    // ---------- health ----------

    @Test
    public void healthReturnsOk() throws Exception {
        Res r = get("/books/health");
        assertEquals(200, r.status);
        assertEquals("OK", r.body);
    }

    @Test
    public void healthRejectsOtherMethods() throws Exception {
        assertEquals(405, send("POST", "/books/health", "{}").status);
    }

    // ---------- create ----------

    @Test
    public void createReturnsIncrementingIds() throws Exception {
        assertEquals(1, create("Dune", "Frank Herbert", 1965, 50, "SCI_FI"));
        assertEquals(2, create("Emma", "Jane Austen", 1990, 20, "ROMANCE"));
    }

    @Test
    public void createRejectsDuplicateTitleIgnoringCase() throws Exception {
        create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        Res r = send("POST", "/book", bookJson("DUNE", "Someone Else", 1970, 10, "NOVEL"));
        assertEquals(409, r.status);
        assertTrue(r.error().contains("already exists"));
    }

    @Test
    public void createAcceptsYearBoundaries() throws Exception {
        assertEquals(200, send("POST", "/book", bookJson("Low", "A", 1940, 1, "NOVEL")).status);
        assertEquals(200, send("POST", "/book", bookJson("High", "A", 2100, 1, "NOVEL")).status);
    }

    @Test
    public void createRejectsYearOutsideRange() throws Exception {
        Res tooOld = send("POST", "/book", bookJson("Old", "A", 1939, 10, "NOVEL"));
        assertEquals(409, tooOld.status);
        assertTrue(tooOld.error().contains("1939"));
        assertEquals(409, send("POST", "/book", bookJson("Future", "A", 2101, 10, "NOVEL")).status);
        assertEquals(0, get("/books/total").result().getAsInt());
    }

    @Test
    public void createRejectsZeroAndNegativePrice() throws Exception {
        assertEquals(409, send("POST", "/book", bookJson("Free", "A", 2000, 0, "NOVEL")).status);
        assertEquals(409, send("POST", "/book", bookJson("Negative", "A", 2000, -5, "NOVEL")).status);
    }

    @Test
    public void createRejectsInvalidJson() throws Exception {
        assertEquals(400, send("POST", "/book", "{not json").status);
    }

    @Test
    public void createRejectsMissingFields() throws Exception {
        assertEquals(400, send("POST", "/book", "{\"title\":\"No author\"}").status);
        assertEquals(400, send("POST", "/book",
            "{\"title\":\"T\",\"author\":\"A\",\"year\":\"abc\",\"price\":5,\"genres\":[]}").status);
    }

    /** Regression: unknown genres used to be stored, then could never be found by the genre filter. */
    @Test
    public void createRejectsUnknownGenre() throws Exception {
        Res r = send("POST", "/book", bookJson("Odd", "A", 2000, 10, "NOVEL", "COOKING"));
        assertEquals(400, r.status);
        assertTrue(r.body.contains("COOKING"));
        assertEquals(0, get("/books/total").result().getAsInt());
    }

    /** Regression partner of the one above: every stored book is reachable through its genres. */
    @Test
    public void everyCreatedBookIsFoundByItsGenres() throws Exception {
        create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        create("Emma", "Jane Austen", 1990, 20, "ROMANCE");
        assertEquals(List.of("Dune"), titles(get("/books?genres=SCI_FI")));
        assertEquals(List.of("Emma"), titles(get("/books?genres=ROMANCE")));
    }

    // ---------- get / update / delete one book ----------

    @Test
    public void getBookById() throws Exception {
        int id = create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        Res r = get("/book?id=" + id);
        assertEquals(200, r.status);
        JsonObject book = r.result().getAsJsonObject();
        assertEquals("Dune", book.get("title").getAsString());
        assertEquals("Frank Herbert", book.get("author").getAsString());
        assertEquals(1965, book.get("year").getAsInt());
        assertEquals(50, book.get("price").getAsInt());
        assertEquals("SCI_FI", book.get("genres").getAsJsonArray().get(0).getAsString());
    }

    @Test
    public void getUnknownBookIs404() throws Exception {
        Res r = get("/book?id=99");
        assertEquals(404, r.status);
        assertTrue(r.error().contains("99"));
    }

    @Test
    public void getWithBadIdIs400() throws Exception {
        assertEquals(400, get("/book?id=abc").status);
        assertEquals(400, get("/book").status);
    }

    @Test
    public void updatePriceReturnsOldPrice() throws Exception {
        int id = create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        Res r = put("/book?id=" + id + "&price=75");
        assertEquals(200, r.status);
        assertEquals(50, r.result().getAsInt());
        assertEquals(75, get("/book?id=" + id).result().getAsJsonObject().get("price").getAsInt());
    }

    @Test
    public void updateRejectsNonPositivePrice() throws Exception {
        int id = create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        assertEquals(409, put("/book?id=" + id + "&price=0").status);
        assertEquals(50, get("/book?id=" + id).result().getAsJsonObject().get("price").getAsInt());
    }

    @Test
    public void updateErrors() throws Exception {
        assertEquals(404, put("/book?id=42&price=10").status);
        int id = create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        assertEquals(400, put("/book?id=" + id).status);
        assertEquals(400, put("/book?id=" + id + "&price=abc").status);
        assertEquals(400, put("/book?price=10").status);
    }

    @Test
    public void deleteReturnsRemainingCount() throws Exception {
        int first = create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        create("Emma", "Jane Austen", 1990, 20, "ROMANCE");
        Res r = delete("/book?id=" + first);
        assertEquals(200, r.status);
        assertEquals(1, r.result().getAsInt());
        assertEquals(404, get("/book?id=" + first).status);
    }

    @Test
    public void deleteErrors() throws Exception {
        assertEquals(404, delete("/book?id=7").status);
        assertEquals(400, delete("/book?id=x").status);
    }

    @Test
    public void idsAreNotReusedAfterDelete() throws Exception {
        int first = create("Dune", "Frank Herbert", 1965, 50, "SCI_FI");
        delete("/book?id=" + first);
        assertEquals(first + 1, create("Emma", "Jane Austen", 1990, 20, "ROMANCE"));
    }

    @Test
    public void bookEndpointRejectsOtherMethods() throws Exception {
        assertEquals(405, send("PATCH", "/book?id=1", null).status);
    }

    // ---------- listing and filtering ----------

    @Test
    public void listIsSortedByTitleIgnoringCase() throws Exception {
        create("banana", "A", 2000, 10, "NOVEL");
        create("Apple", "A", 2000, 10, "NOVEL");
        create("cherry", "A", 2000, 10, "NOVEL");
        assertEquals(List.of("Apple", "banana", "cherry"), titles(get("/books")));
    }

    @Test
    public void emptyCatalogueReturnsEmptyList() throws Exception {
        Res r = get("/books");
        assertEquals(200, r.status);
        assertEquals(0, r.result().getAsJsonArray().size());
        assertEquals(0, get("/books/total").result().getAsInt());
    }

    @Test
    public void filterByAuthorIsExactAndCaseInsensitive() throws Exception {
        seedCatalogue();
        assertEquals(List.of("Dune"), titles(get("/books?author=frank%20herbert")));
        assertEquals(List.of(), titles(get("/books?author=frank")));
    }

    @Test
    public void priceBoundsAreInclusive() throws Exception {
        seedCatalogue();
        assertEquals(List.of("Clean Code", "Dune", "Sapiens"), titles(get("/books?price-bigger-than=50")));
        assertEquals(List.of("Berserk", "Dune", "Neuromancer"), titles(get("/books?price-less-than=50")));
        assertEquals(List.of("Dune"), titles(get("/books?price-bigger-than=50&price-less-than=50")));
    }

    @Test
    public void yearBoundsAreInclusive() throws Exception {
        seedCatalogue();
        assertEquals(List.of("Clean Code", "Sapiens"), titles(get("/books?year-bigger-than=2008")));
        assertEquals(List.of("Dune", "Neuromancer"), titles(get("/books?year-less-than=1984")));
    }

    @Test
    public void genreFilterMatchesAnyListedGenre() throws Exception {
        seedCatalogue();
        assertEquals(List.of("Dune", "Neuromancer"), titles(get("/books?genres=SCI_FI")));
        assertEquals(List.of("Berserk", "Dune", "Neuromancer"), titles(get("/books?genres=SCI_FI,MANGA")));
        assertEquals(List.of("Neuromancer"), titles(get("/books?genres=NOVEL")));
    }

    @Test
    public void filtersCombineWithAnd() throws Exception {
        seedCatalogue();
        assertEquals(List.of("Neuromancer"), titles(get("/books?genres=SCI_FI&price-less-than=45")));
        assertEquals(List.of(), titles(get("/books?genres=MANGA&price-bigger-than=100")));
    }

    @Test
    public void totalMatchesListSize() throws Exception {
        seedCatalogue();
        assertEquals(5, get("/books/total").result().getAsInt());
        assertEquals(2, get("/books/total?genres=SCI_FI").result().getAsInt());
        assertEquals(3, get("/books/total?price-bigger-than=50").result().getAsInt());
    }

    @Test
    public void invalidFilterValuesAre400() throws Exception {
        assertEquals(400, get("/books?price-bigger-than=abc").status);
        assertEquals(400, get("/books?year-less-than=1.5").status);
        assertEquals(400, get("/books?genres=SCI_FI,COOKING").status);
        assertEquals(400, get("/books/total?price-less-than=x").status);
        assertEquals(400, get("/books/total?genres=NOPE").status);
    }

    @Test
    public void listingRejectsOtherMethods() throws Exception {
        assertEquals(405, send("POST", "/books", "{}").status);
        assertEquals(405, send("DELETE", "/books/total", null).status);
    }

    // ---------- logger levels ----------

    @Test
    public void loggerLevelCanBeReadAndChanged() throws Exception {
        String original = get("/logs/level?logger-name=books-logger").body;
        try {
            Res changed = put("/logs/level?logger-name=books-logger&logger-level=DEBUG");
            assertEquals(200, changed.status);
            assertEquals("DEBUG", changed.body);
            assertEquals("DEBUG", get("/logs/level?logger-name=books-logger").body);
        } finally {
            put("/logs/level?logger-name=books-logger&logger-level=" + original);
        }
    }

    @Test
    public void loggerLevelErrors() throws Exception {
        assertEquals(400, get("/logs/level?logger-name=nope").status);
        assertEquals(400, put("/logs/level?logger-name=books-logger&logger-level=LOUD").status);
        assertEquals(400, put("/logs/level?logger-name=nope&logger-level=INFO").status);
        assertEquals(405, send("POST", "/logs/level?logger-name=books-logger", null).status);
    }

    // ---------- plain unit tests for the helpers ----------

    @Test
    public void parseQueryDecodesAndHandlesEdgeCases() {
        Map<String, String> q = BooksServer.parseQuery("author=frank%20herbert&flag&genres=SCI_FI,MANGA");
        assertEquals("frank herbert", q.get("author"));
        assertEquals("", q.get("flag"));
        assertEquals("SCI_FI,MANGA", q.get("genres"));
        assertTrue(BooksServer.parseQuery(null).isEmpty());
        assertTrue(BooksServer.parseQuery("").isEmpty());
    }

    @Test
    public void validateFilterParamsAcceptsGoodInput() {
        BooksServer.validateFilterParams(Map.of(
            "price-bigger-than", "10", "genres", "SCI_FI, MANGA"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateFilterParamsRejectsBadNumber() {
        BooksServer.validateFilterParams(Map.of("year-bigger-than", "soon"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateFilterParamsRejectsUnknownGenre() {
        BooksServer.validateFilterParams(Map.of("genres", "SCI_FI,COOKING"));
    }
}
