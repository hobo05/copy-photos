/*
 * Copyright 2013 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chengsoft.commands;

import com.google.common.collect.Lists;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.shell.Bootstrap;
import org.springframework.shell.core.CommandResult;
import org.springframework.shell.core.Completion;
import org.springframework.shell.core.JLineShellComponent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;

@Ignore
public class PhotoCommandTests {

	@Test
	public void testSimple() {
		Bootstrap bootstrap = new Bootstrap();
		
		JLineShellComponent shell = bootstrap.getJLineShellComponent();
		
		CommandResult cr = shell.executeCommand("hw simple --message hello");
		assertEquals(true, cr.isSuccess());
		assertEquals("Message = [hello] Location = [null]", cr.getResult());
	}

	@Test
	public void test() throws Exception {
		List<Completion> completions = Lists.newArrayList();
		Path existingPath = Paths.get("/Users/tche");
		Path parent = existingPath.getParent();
		System.out.println("parent="+parent);
		if (Files.exists(parent)) {
			try {
				Files.walk(parent, 1).forEach(p -> completions.add(new Completion(p.getFileName().toString())));
			} catch (IOException e) {
				throw new IllegalArgumentException("Could not get autocomplete values", e);
			}
		}

		System.out.println(completions);

	}
}
