/**
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.sga.apigateway.websocket;

import com.datastax.oss.sga.api.gateway.GatewayAuthenticationProvider;
import com.datastax.oss.sga.api.gateway.GatewayAuthenticationResult;
import com.datastax.oss.sga.api.gateway.GatewayRequestContext;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestGatewayAuthenticationProvider implements GatewayAuthenticationProvider {

    @Override
    public String type() {
        return "test-auth";
    }

    @Override
    public void initialize(Map<String, Object> configuration) {
    }

    @Override
    public GatewayAuthenticationResult authenticate(GatewayRequestContext context) {
        log.info("Authenticating {}", context.credentials());
        if (context.credentials().startsWith("test-user-password")) {
            return GatewayAuthenticationResult.authenticationSuccessful(Map.of("user-id", context.credentials()));
        } else {
            return GatewayAuthenticationResult.authenticationFailed("Invalid credentials");
        }
    }
}