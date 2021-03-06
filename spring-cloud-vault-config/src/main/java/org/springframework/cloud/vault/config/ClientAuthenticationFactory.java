/*
 * Copyright 2017-2018 the original author or authors.
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
package org.springframework.cloud.vault.config;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.cloud.vault.config.VaultProperties.AwsIamProperties;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.vault.authentication.AppIdAuthentication;
import org.springframework.vault.authentication.AppIdAuthenticationOptions;
import org.springframework.vault.authentication.AppIdUserIdMechanism;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.AwsEc2Authentication;
import org.springframework.vault.authentication.AwsEc2AuthenticationOptions;
import org.springframework.vault.authentication.AwsIamAuthentication;
import org.springframework.vault.authentication.AwsIamAuthenticationOptions;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.ClientCertificateAuthentication;
import org.springframework.vault.authentication.CubbyholeAuthentication;
import org.springframework.vault.authentication.CubbyholeAuthenticationOptions;
import org.springframework.vault.authentication.IpAddressUserId;
import org.springframework.vault.authentication.KubernetesAuthentication;
import org.springframework.vault.authentication.KubernetesAuthenticationOptions;
import org.springframework.vault.authentication.KubernetesServiceAccountTokenFile;
import org.springframework.vault.authentication.MacAddressUserId;
import org.springframework.vault.authentication.StaticUserId;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.authentication.AwsEc2AuthenticationOptions.Nonce;
import org.springframework.vault.authentication.AwsIamAuthenticationOptions.AwsIamAuthenticationOptionsBuilder;
import org.springframework.vault.support.VaultToken;
import org.springframework.web.client.RestOperations;

/**
 * Factory for {@link ClientAuthentication}.
 *
 * @author Mark Paluch
 * @author Kevin Holditch
 * @author Michal Budzyn
 * @since 1.1
 */
@RequiredArgsConstructor
class ClientAuthenticationFactory {

	private final VaultProperties vaultProperties;

	private final RestOperations restOperations;

	/**
	 * @return a new {@link ClientAuthentication}.
	 */
	ClientAuthentication createClientAuthentication() {

		switch (vaultProperties.getAuthentication()) {

		case TOKEN:
			Assert.hasText(vaultProperties.getToken(),
					"Token (spring.cloud.vault.token) must not be empty");
			return new TokenAuthentication(vaultProperties.getToken());

		case APPID:
			return appIdAuthentication(vaultProperties);

		case APPROLE:
			return appRoleAuthentication(vaultProperties);

		case CERT:
			return new ClientCertificateAuthentication(restOperations);

		case AWS_EC2:
			return awsEc2Authentication(vaultProperties);

		case AWS_IAM:
			return awsIamAuthentication(vaultProperties);

		case CUBBYHOLE:
			return cubbyholeAuthentication();

		case KUBERNETES:
			return kubernetesAuthentication(vaultProperties);
		}

		throw new UnsupportedOperationException(String.format(
				"Client authentication %s not supported",
				vaultProperties.getAuthentication()));
	}

	private ClientAuthentication appIdAuthentication(VaultProperties vaultProperties) {

		VaultProperties.AppIdProperties appId = vaultProperties.getAppId();
		Assert.hasText(appId.getUserId(),
				"UserId (spring.cloud.vault.app-id.user-id) must not be empty");

		AppIdAuthenticationOptions authenticationOptions = AppIdAuthenticationOptions
				.builder().appId(vaultProperties.getApplicationName()) //
				.path(appId.getAppIdPath()) //
				.userIdMechanism(getClientAuthentication(appId)).build();

		return new AppIdAuthentication(authenticationOptions, restOperations);
	}

	private AppIdUserIdMechanism getClientAuthentication(
			VaultProperties.AppIdProperties appId) {

		try {
			Class<?> userIdClass = ClassUtils.forName(appId.getUserId(), null);
			return (AppIdUserIdMechanism) BeanUtils.instantiateClass(userIdClass);
		}
		catch (ClassNotFoundException ex) {

			switch (appId.getUserId().toUpperCase()) {

			case VaultProperties.AppIdProperties.IP_ADDRESS:
				return new IpAddressUserId();

			case VaultProperties.AppIdProperties.MAC_ADDRESS:

				if (StringUtils.hasText(appId.getNetworkInterface())) {
					try {
						return new MacAddressUserId(Integer.parseInt(appId
								.getNetworkInterface()));
					}
					catch (NumberFormatException e) {
						return new MacAddressUserId(appId.getNetworkInterface());
					}
				}

				return new MacAddressUserId();
			default:
				return new StaticUserId(appId.getUserId());
			}
		}
	}

	private ClientAuthentication appRoleAuthentication(VaultProperties vaultProperties) {

		VaultProperties.AppRoleProperties appRole = vaultProperties.getAppRole();
		Assert.hasText(appRole.getRoleId(),
				"RoleId (spring.cloud.vault.app-role.role-id) must not be empty");

		AppRoleAuthenticationOptions.AppRoleAuthenticationOptionsBuilder builder = AppRoleAuthenticationOptions
				.builder().path(appRole.getAppRolePath()).roleId(appRole.getRoleId());

		if (StringUtils.hasText(appRole.getSecretId())) {
			builder = builder.secretId(appRole.getSecretId());
		}

		return new AppRoleAuthentication(builder.build(), restOperations);
	}

	private ClientAuthentication awsEc2Authentication(VaultProperties vaultProperties) {

		VaultProperties.AwsEc2Properties awsEc2 = vaultProperties.getAwsEc2();

		Nonce nonce = StringUtils.hasText(awsEc2.getNonce()) ? Nonce.provided(awsEc2
				.getNonce().toCharArray()) : Nonce.generated();

		AwsEc2AuthenticationOptions authenticationOptions = AwsEc2AuthenticationOptions
				.builder().role(awsEc2.getRole()) //
				.path(awsEc2.getAwsEc2Path()) //
				.nonce(nonce) //
				.identityDocumentUri(URI.create(awsEc2.getIdentityDocument())) //
				.build();

		return new AwsEc2Authentication(authenticationOptions, restOperations,
				restOperations);
	}

	private ClientAuthentication awsIamAuthentication(VaultProperties vaultProperties) {

		AwsIamProperties awsIam = vaultProperties.getAwsIam();

		AWSCredentialsProvider credentialsProvider = AwsCredentialProvider
				.getAwsCredentialsProvider();

		AwsIamAuthenticationOptionsBuilder builder = AwsIamAuthenticationOptions
				.builder();

		if (StringUtils.hasText(awsIam.getRole())) {
			builder.role(awsIam.getRole());
		}

		if (StringUtils.hasText(awsIam.getServerName())) {
			builder.serverName(awsIam.getServerName());
		}

		builder.path(awsIam.getAwsPath()) //
				.credentialsProvider(credentialsProvider);

		AwsIamAuthenticationOptions options = builder.credentialsProvider(
				credentialsProvider).build();

		return new AwsIamAuthentication(options, restOperations);
	}

	private ClientAuthentication cubbyholeAuthentication() {

		Assert.hasText(vaultProperties.getToken(),
				"Initial Token (spring.cloud.vault.token) for Cubbyhole authentication must not be empty");

		CubbyholeAuthenticationOptions options = CubbyholeAuthenticationOptions.builder() //
				.wrapped() //
				.initialToken(VaultToken.of(vaultProperties.getToken())) //
				.build();

		return new CubbyholeAuthentication(options, restOperations);
	}

	private ClientAuthentication kubernetesAuthentication(VaultProperties vaultProperties) {

		VaultProperties.KubernetesProperties kubernetes = vaultProperties.getKubernetes();

		Assert.hasText(kubernetes.getRole(),
				"Role (spring.cloud.vault.kubernetes.role) must not be empty");
		Assert.hasText(
				kubernetes.getServiceAccountTokenFile(),
				"Service account token file (spring.cloud.vault.kubernetes.service-account-token-file) must not be empty");

		KubernetesAuthenticationOptions options = KubernetesAuthenticationOptions
				.builder()
				.path(kubernetes.getKubernetesPath())
				.role(kubernetes.getRole())
				.jwtSupplier(
						new KubernetesServiceAccountTokenFile(kubernetes
								.getServiceAccountTokenFile())).build();

		return new KubernetesAuthentication(options, restOperations);
	}

	private static class AwsCredentialProvider {

		private static AWSCredentialsProvider getAwsCredentialsProvider() {

			DefaultAWSCredentialsProviderChain backingCredentialsProvider = DefaultAWSCredentialsProviderChain
					.getInstance();

			// Eagerly fetch credentials preventing lag during the first, actual login.
			AWSCredentials firstAccess = backingCredentialsProvider.getCredentials();

			AtomicReference<AWSCredentials> once = new AtomicReference<>(firstAccess);

			return new AWSCredentialsProvider() {

				@Override
				public AWSCredentials getCredentials() {

					if (once.compareAndSet(firstAccess, null)) {
						return firstAccess;
					}

					return backingCredentialsProvider.getCredentials();
				}

				@Override
				public void refresh() {
					backingCredentialsProvider.refresh();
				}
			};
		}
	}
}
