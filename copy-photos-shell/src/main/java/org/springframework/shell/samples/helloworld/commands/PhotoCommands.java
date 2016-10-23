package org.springframework.shell.samples.helloworld.commands;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.JLineShellComponent;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.stereotype.Component;

import java.util.logging.Level;

@Component
public class PhotoCommands implements CommandMarker {

	@Autowired
	private JLineShellComponent shell;

	@CliCommand(value = "start-copy", help = "Starts the process of copying photos")
	public String simple() throws InterruptedException {

		shell.flash(Level.ALL, "start", "id");
		Thread.sleep(2000L);
		shell.flash(Level.ALL, "middle", "id2");
		Thread.sleep(2000L);
		shell.flash(Level.ALL, "end", "id3");
		Thread.sleep(2000L);

		return "hello";
	}
}
