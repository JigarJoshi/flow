/*
 * Copyright 2015 Lithium Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lithium.flow.filer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Predicate;

/**
 * @author Matt Ayres
 */
public class RegexPathPredicate implements Predicate<Record> {
	private final List<Pattern> patterns;

	public RegexPathPredicate(@Nonnull List<String> regexes) {
		checkNotNull(regexes);
		patterns = regexes.stream().map(Pattern::compile).collect(toList());
	}

	@Override
	public boolean apply(@Nullable Record record) {
		if (record != null) {
			String path = record.getPath();
			for (Pattern pattern : patterns) {
				if (pattern.matcher(path).matches()) {
					return true;
				}
			}
		}
		return false;
	}
}
