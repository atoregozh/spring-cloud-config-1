/*
 * Copyright 2013-2015 the original author or authors.
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
package org.springframework.cloud.config.server.encryption;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.config.server.ConfigServerProperties;
import org.springframework.cloud.context.encrypt.KeyFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.rsa.crypto.RsaKeyHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Dave Syer
 *
 */
@RestController
@RequestMapping("${spring.cloud.config.server.prefix:}")
public class EncryptionController {

	private static Log logger = LogFactory.getLog(EncryptionController.class);

	volatile private TextEncryptorLocator encryptor;

	private final ConfigServerProperties properties;

	private EnvironmentPrefixHelper helper = new EnvironmentPrefixHelper();

	public EncryptionController(TextEncryptorLocator encryptor,
			ConfigServerProperties configServerProperties) {
		this.encryptor = encryptor;
		this.properties = configServerProperties;
	}

	@RequestMapping(value = "/key", method = RequestMethod.GET)
	public String getPublicKey() {
		TextEncryptor encryptor = this.encryptor.locate(this.helper.getEncryptorKeys(
				"application", "default", ""));
		if (!(encryptor instanceof RsaKeyHolder)) {
			throw new KeyNotAvailableException();
		}
		return ((RsaKeyHolder) encryptor).getPublicKey();
	}

	@RequestMapping(value = "/key/{name}/{profiles}", method = RequestMethod.GET)
	public String getPublicKey(@PathVariable String name, @PathVariable String profiles) {
		TextEncryptor encryptor = this.encryptor.locate(this.helper.getEncryptorKeys(
				name, profiles, ""));
		if (!(encryptor instanceof RsaKeyHolder)) {
			throw new KeyNotAvailableException();
		}
		return ((RsaKeyHolder) encryptor).getPublicKey();
	}

	@ExceptionHandler(KeyFormatException.class)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> keyFormat() {
		Map<String, Object> body = new HashMap<>();
		body.put("status", "BAD_REQUEST");
		body.put("description", "Key data not in correct format (PEM or jks keystore)");
		return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(KeyNotAvailableException.class)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> keyUnavailable() {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("status", "NOT_FOUND");
		body.put("description", "No public key available");
		return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
	}

	@RequestMapping(value = "encrypt/status", method = RequestMethod.GET)
	public Map<String, Object> status() {
		checkEncryptorInstalled("application", "default");
		return Collections.<String, Object> singletonMap("status", "OK");
	}

	@RequestMapping(value = "encrypt", method = RequestMethod.POST)
	public String encrypt(@RequestBody String data,
			@RequestHeader("Content-Type") MediaType type) {

		return encrypt(this.properties.getDefaultApplicationName(),
				this.properties.getDefaultProfile(), data, type);
	}

	@RequestMapping(value = "/encrypt/{name}/{profiles}", method = RequestMethod.POST)
	public String encrypt(@PathVariable String name, @PathVariable String profiles,
			@RequestBody String data, @RequestHeader("Content-Type") MediaType type) {
		checkEncryptorInstalled(name, profiles);
		try {
			String input = stripFormData(data, type, false);
			Map<String, String> keys = this.helper
					.getEncryptorKeys(name, profiles, input);
			String encrypted = this.helper.addPrefix(keys, this.encryptor.locate(keys)
					.encrypt(input));
			logger.info("Encrypted data");
			return encrypted;
		}
		catch (IllegalArgumentException e) {
			throw new InvalidCipherException();
		}
	}

	@RequestMapping(value = "decrypt", method = RequestMethod.POST)
	public String decrypt(@RequestBody String data,
			@RequestHeader("Content-Type") MediaType type) {

		return decrypt(this.properties.getDefaultApplicationName(),
				this.properties.getDefaultProfile(), data, type);
	}

	@RequestMapping(value = "/decrypt/{name}/{profiles}", method = RequestMethod.POST)
	public String decrypt(@PathVariable String name, @PathVariable String profiles,
			@RequestBody String data, @RequestHeader("Content-Type") MediaType type) {
		checkEncryptorInstalled(name, profiles);
		try {
			String input = stripFormData(data, type, true);
			Map<String, String> encryptorKeys = this.helper.getEncryptorKeys(name,
					profiles, input);
			TextEncryptor encryptor = this.encryptor.locate(encryptorKeys);
			String encryptedText = this.helper.stripPrefix(input);
			String decrypted = this.helper.stripPrefix(encryptor.decrypt(encryptedText));
			logger.info("Decrypted cipher data");
			return decrypted;
		}
		catch (IllegalArgumentException e) {
			throw new InvalidCipherException();
		}
	}

	private void checkEncryptorInstalled(String name, String profiles) {
		if (this.encryptor == null
				|| this.encryptor
				.locate(this.helper.getEncryptorKeys(name, profiles, ""))
				.encrypt("FOO").equals("FOO")) {
			throw new KeyNotInstalledException();
		}
	}

	private String stripFormData(String data, MediaType type, boolean cipher) {

		if (data.endsWith("=") && !type.equals(MediaType.TEXT_PLAIN)) {
			try {
				data = URLDecoder.decode(data, "UTF-8");
				if (cipher) {
					data = data.replace(" ", "+");
				}
			}
			catch (UnsupportedEncodingException e) {
				// Really?
			}
			String candidate = data.substring(0, data.length() - 1);
			if (cipher) {
				if (data.endsWith("=")) {
					if (data.length() / 2 != (data.length() + 1) / 2) {
						try {
							Hex.decode(candidate);
							return candidate;
						}
						catch (IllegalArgumentException e) {
							if (Base64.isBase64(data.getBytes())) {
								return data;
							}
						}
					}
				}
				return data;
			}
			// User posted data with content type form but meant it to be text/plain
			data = candidate;
		}

		return data;

	}

	@ExceptionHandler(KeyNotInstalledException.class)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> notInstalled() {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("status", "NO_KEY");
		body.put("description", "No key was installed for encryption service");
		return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
	}

	@ExceptionHandler(InvalidCipherException.class)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> invalidCipher() {
		Map<String, Object> body = new HashMap<String, Object>();
		body.put("status", "INVALID");
		body.put("description", "Text not encrypted with this key");
		return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
	}

}

@SuppressWarnings("serial")
class KeyNotInstalledException extends RuntimeException {
}

@SuppressWarnings("serial")
class KeyNotAvailableException extends RuntimeException {
}

@SuppressWarnings("serial")
class InvalidCipherException extends RuntimeException {
}
