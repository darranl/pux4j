module dev.pux4j.ui.core {
    requires com.pi4j;
    requires jakarta.json;
    requires org.slf4j;

    exports dev.pux4j.ui.core;

    uses dev.pux4j.ui.core.DisplayDriverFactory;
    uses dev.pux4j.ui.core.TouchDriverFactory;

    provides dev.pux4j.ui.core.DisplayDriverFactory
        with dev.pux4j.ui.core.internal.PngDisplayDriverFactory;
}
