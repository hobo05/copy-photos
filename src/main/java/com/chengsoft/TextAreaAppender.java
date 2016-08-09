package com.chengsoft;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.status.StatusLogger;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;

/**
 * Created by Tim on 8/8/2016.
 */
@Plugin(name = "TextArea", category = "Core", elementType = "appender", printObject = true)
public class TextAreaAppender extends AbstractAppender {

    protected static final org.apache.logging.log4j.Logger LOGGER = StatusLogger.getLogger();

    private static volatile TextArea textArea = null;

    protected TextAreaAppender(String name, Filter filter, Layout<? extends Serializable> layout) {
        super(name, filter, layout);
    }

    @PluginFactory
    public static TextAreaAppender createAppender(
            @PluginAttribute("name") final String name,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") final Filter filter) {
        if (name == null) {
            LOGGER.error("No name provided for TextAreaAppender");
            return null;
        }
        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }
        return new TextAreaAppender(name, filter, layout);
    }

    /**
     * Set the target TextArea for the logging information to appear.
     *
     * @param textArea
     */
    public static void setTextArea(final TextArea textArea) {
        TextAreaAppender.textArea = textArea;
    }

    @Override
    public void append(LogEvent event) {
        final String message;
        try {
            message = new String(getLayout().toByteArray(event), "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Could not format logevent text:", e);
        }

        // Append formatted message to text area using the Thread.
        try {
            Platform.runLater(() -> {
                try {
                    if (textArea != null) {
                        if (textArea.getText().length() == 0) {
                            textArea.setText(message);
                        } else {
                            textArea.selectEnd();
                            textArea.insertText(textArea.getText().length(),
                                    message);
                        }
                    }
                } catch (final Throwable t) {
                    LOGGER.error("Unable to append log to text area: ",t);
                }
            });
        } catch (final IllegalStateException e) {
            // ignore case when the platform hasn't yet been iniitialized
        }
    }
}
