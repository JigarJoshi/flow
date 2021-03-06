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

package com.lithium.flow.vault;

import static com.google.common.base.Preconditions.checkNotNull;

import com.lithium.flow.access.Access;
import com.lithium.flow.access.Prompt;
import com.lithium.flow.access.prompts.DialogPrompt;
import com.lithium.flow.access.prompts.MemoryPrompt;
import com.lithium.flow.access.prompts.NoPrompt;
import com.lithium.flow.access.prompts.SyncPrompt;
import com.lithium.flow.config.Config;
import com.lithium.flow.ioc.Locator;
import com.lithium.flow.shell.ShellAccess;
import com.lithium.flow.shell.Shells;
import com.lithium.flow.store.FileStore;
import com.lithium.flow.store.MemoryStore;
import com.lithium.flow.store.Store;
import com.lithium.flow.util.Logs;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;

import javax.annotation.Nonnull;

import org.slf4j.Logger;

import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;

/**
 * @author Matt Ayres
 */
public class Vaults {
	private static final Logger log = Logs.getLogger();
	private static final Map<String, Store> stores = Maps.newConcurrentMap();

	@Nonnull
	public static Access buildAccess(@Nonnull Locator locator) {
		checkNotNull(locator);
		return buildAccess(locator.getInstance(Config.class), locator.getInstance(Vault.class));
	}

	@Nonnull
	public static Access buildAccess(@Nonnull Config config) throws IOException {
		return buildAccess(config, buildVault(config));
	}

	@Nonnull
	public static Access buildAccess(@Nonnull Config config, @Nonnull Vault vault) {
		checkNotNull(config);
		checkNotNull(vault);

		if (!config.getBoolean("vault", true)) {
			return Shells.buildAccess(config);
		}

		Prompt prompt = config.getBoolean("vault.prompt", true) ? new DialogPrompt("Vault") : new NoPrompt();
		prompt = new MemoryPrompt(prompt);
		prompt = new VaultPrompt(prompt, vault);
		prompt = new SyncPrompt(prompt);

		Access access = new ShellAccess(config, prompt);
		access = new VaultAccess(access, vault);
		return access;
	}

	@Nonnull
	public static Vault buildVault(@Nonnull Locator locator) throws IOException {
		checkNotNull(locator);
		return buildVault(locator.getInstance(Config.class));
	}

	@Nonnull
	public static Vault buildVault(@Nonnull Config config) throws IOException {
		checkNotNull(config);

		String path = config.getString("vault.path", System.getProperty("user.home") + "/.vault");
		File file = new File(path);
		log.info("vault path: {}", path);

		Store store;
		if (file.exists()) {
			store = new FileStore(file);
			if (config.getBoolean("vault.delete", false)) {
				log.info("deleting vault");
				store = new MemoryStore(store);
				if (!file.delete()) {
					throw new IOException("failed to delete vault");
				}
				if (config.getBoolean("vault.memory", false)) {
					stores.put(path, store);
				}
			}
		} else if (stores.containsKey(path)) {
			store = stores.get(path);
		} else {
			store = new FileStore(file);
		}

		Vault vault = new SecureVault(config, store);
		if (config.getBoolean("vault.agent", true)) {
			log.info("starting vault agent");
			vault = new AgentVault(vault, store);
		}
		if (config.getBoolean("vault.env", false)) {
			if (!vault.unlock(System.getenv("VAULT_PASSWORD"))) {
				throw new IOException("failed to unlock vault with env: VAULT_PASSWORD");
			}
		}
		if (config.getBoolean("vault.property", false)) {
			if (!vault.unlock(System.getProperty("vault.password"))) {
				throw new IOException("failed to unlock vault with property: vault.password");
			}
		}
		return vault;
	}

	@Nonnull
	public static String securePassword() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return BaseEncoding.base64().encode(bytes);
	}
}
