package io.github.lihanc940.openpulse.desktop;

import javafx.application.Application;

public final class DesktopLauncher {
    private DesktopLauncher() { }
    public static void main(String[] arguments) { Application.launch(DesktopApplication.class, arguments); }
}
