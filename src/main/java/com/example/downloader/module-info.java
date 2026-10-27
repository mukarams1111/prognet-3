module com.example.downloader {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http; // Untuk fitur download nanti

    opens com.example.downloader to javafx.fxml;
    exports com.example.downloader;
}