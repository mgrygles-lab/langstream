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
package ai.langstream.impl.storage.k8s.apps;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class KubernetesApplicationStoreProperties {
    private String namespaceprefix;

    @Data
    @NoArgsConstructor
    public static class DeployerRuntimeConfig {
        private String image;
        @JsonAlias({"image-pull-policy", "imagepullpolicy"})
        private String imagePullPolicy;
    }


    @JsonAlias({"deployer-runtime", "deployerruntime"})
    private DeployerRuntimeConfig deployerRuntime;

}