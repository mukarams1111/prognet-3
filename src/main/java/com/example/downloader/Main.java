package com.example.downloader;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.io.IOException;

public class Main extends Application {

    private PrimaryController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("primary.fxml"));
        Scene scene = new Scene(loader.load(), 950, 550);
        controller = loader.getController();
        stage.setTitle("Download Manager");
        stage.setScene(scene);
        stage.show();
    }

    @Override
    public void stop() throws Exception {
        if (controller != null)
            controller.shutdown();
        super.stop();
    }

    public static void main(String[] args) {
        launch();
    }
}