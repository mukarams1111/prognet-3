module com.example.downloader {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires com.google.gson;

    opens com.example.downloader to javafx.fxml, com.google.gson;
    exports com.example.downloader;
}