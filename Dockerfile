FROM eclipse-temurin:17
WORKDIR /app
COPY lib lib
COPY BooksServer.java .
RUN javac -cp ".:lib/gson-2.10.1.jar" -d . BooksServer.java
EXPOSE 8574
CMD ["java", "-cp", ".:lib/gson-2.10.1.jar", "BooksServer"]
