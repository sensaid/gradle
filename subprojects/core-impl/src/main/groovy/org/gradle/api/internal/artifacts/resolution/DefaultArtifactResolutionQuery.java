/*
 * Copyright 2014 the original author or authors.
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
package org.gradle.api.internal.artifacts.resolution;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.resolution.*;
import org.gradle.api.internal.artifacts.ModuleMetadataProcessor;
import org.gradle.api.internal.artifacts.configurations.ConfigurationContainerInternal;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.artifacts.ivyservice.*;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ErrorHandlingArtifactResolver;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.RepositoryChain;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory;
import org.gradle.api.internal.artifacts.metadata.ComponentArtifactMetaData;
import org.gradle.api.internal.artifacts.metadata.DefaultDependencyMetaData;
import org.gradle.api.internal.artifacts.metadata.ModuleVersionMetaData;
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository;
import org.gradle.internal.Factory;
import org.gradle.internal.Transformers;
import org.gradle.internal.reflect.DirectInstantiator;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.util.CollectionUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class DefaultArtifactResolutionQuery implements ArtifactResolutionQuery {
    private final ConfigurationContainerInternal configurationContainer;
    private final RepositoryHandler repositoryHandler;
    private final ResolveIvyFactory ivyFactory;
    private final ModuleMetadataProcessor metadataProcessor;
    private final CacheLockingManager lockingManager;

    private Set<ComponentIdentifier> componentIds = Sets.newLinkedHashSet();
    private Class<? extends SoftwareComponent<?>> componentType;
    private Set<Class<? extends SoftwareArtifact>> artifactTypes = Sets.newLinkedHashSet();

    public DefaultArtifactResolutionQuery(ConfigurationContainerInternal configurationContainer, RepositoryHandler repositoryHandler,
                                          ResolveIvyFactory ivyFactory, ModuleMetadataProcessor metadataProcessor, CacheLockingManager lockingManager) {
        this.configurationContainer = configurationContainer;
        this.repositoryHandler = repositoryHandler;
        this.ivyFactory = ivyFactory;
        this.metadataProcessor = metadataProcessor;
        this.lockingManager = lockingManager;
    }

    public ArtifactResolutionQuery forComponents(Iterable<? extends ComponentIdentifier> componentIds) {
        CollectionUtils.addAll(this.componentIds, componentIds);
        return this;
    }

    public ArtifactResolutionQuery forComponents(ComponentIdentifier... componentIds) {
        CollectionUtils.addAll(this.componentIds, componentIds);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T extends SoftwareArtifact, U extends SoftwareComponent<T>> ArtifactResolutionQuery withArtifacts(Class<U> componentType, Class<T>... artifactTypes) {
        this.componentType = componentType;
        if (artifactTypes.length == 0) {
            this.artifactTypes = (Set) Sets.newHashSet(JvmLibrarySourcesArtifact.class, JvmLibraryJavadocArtifact.class);
        } else {
            this.artifactTypes.addAll(Arrays.asList(artifactTypes));
        }
        return this;
    }

    // TODO:DAZ This is ugly and needs a major cleanup and unit tests
    // TODO:DAZ Also need to add a 'result' layer to the api
    public ArtifactResolutionQueryResult execute() {
        List<ResolutionAwareRepository> repositories = CollectionUtils.collect(repositoryHandler, Transformers.cast(ResolutionAwareRepository.class));
        ConfigurationInternal configuration = configurationContainer.detachedConfiguration();
        final RepositoryChain repositoryChain = ivyFactory.create(configuration, repositories, metadataProcessor);
        final ArtifactResolver artifactResolver = new ErrorHandlingArtifactResolver(repositoryChain.getArtifactResolver());

        return lockingManager.useCache("resolve artifacts", new Factory<ArtifactResolutionQueryResult>() {
            public ArtifactResolutionQueryResult create() {
                Set<JvmLibrary> jvmLibraries = Sets.newHashSet();
                Set<UnresolvedSoftwareComponent> unresolvedComponents = Sets.newHashSet();

                for (ComponentIdentifier componentId : componentIds) {
                    if (!(componentId instanceof ModuleComponentIdentifier)) {
                        throw new IllegalArgumentException(String.format("Cannot resolve the artifacts for component %s with unsupported type %s.", componentId.getDisplayName(), componentId.getClass().getName()));
                    }
                    ModuleComponentIdentifier moduleComponentId = (ModuleComponentIdentifier) componentId;
                    BuildableComponentResolveResult moduleResolveResult = new DefaultBuildableComponentResolveResult();
                    repositoryChain.getDependencyResolver().resolve(new DefaultDependencyMetaData(new DefaultDependencyDescriptor(toModuleRevisionId(moduleComponentId), true)), moduleResolveResult);

                    if (moduleResolveResult.getFailure() != null) {
                        unresolvedComponents.add(new DefaultUnresolvedSoftwareComponent(moduleComponentId, moduleResolveResult.getFailure()));
                    } else {
                        DefaultJvmLibrary jvmLibrary = buildJvmLibrary((ModuleVersionMetaData) moduleResolveResult.getMetaData(), artifactResolver);
                        jvmLibraries.add(jvmLibrary);
                    }
                }

                return new DefaultArtifactResolutionQueryResult(jvmLibraries, unresolvedComponents);
            }
        });
    }

    private DefaultJvmLibrary buildJvmLibrary(ModuleVersionMetaData component, ArtifactResolver artifactResolver) {
        // TODO:DAZ These should be 'uninitialised' (failing), not empty
        SoftwareArtifactSet<JvmLibraryJavadocArtifact> javadocs = new DefaultSoftwareArtifactSet<JvmLibraryJavadocArtifact>(Lists.<JvmLibraryJavadocArtifact>newArrayList());
        SoftwareArtifactSet<JvmLibrarySourcesArtifact> sources = new DefaultSoftwareArtifactSet<JvmLibrarySourcesArtifact>(Lists.<JvmLibrarySourcesArtifact>newArrayList());
        for (Class<? extends SoftwareArtifact> artifactType : artifactTypes) {
            if (artifactType == JvmLibraryJavadocArtifact.class) {
                javadocs = buildJvmLibrarySoftwareArtifactSet(JvmLibraryJavadocArtifact.class, DefaultJvmLibraryJavadocArtifact.class, component, artifactResolver);
            } else if (artifactType == JvmLibrarySourcesArtifact.class) {
                sources = buildJvmLibrarySoftwareArtifactSet(JvmLibrarySourcesArtifact.class, DefaultJvmLibrarySourcesArtifact.class, component, artifactResolver);
            } else {
                throw new IllegalArgumentException(String.format("Cannot resolve artifacts with unsupported type %s.", artifactType.getName()));
            }
        }
        return new DefaultJvmLibrary(component.getComponentId(), sources, javadocs);
    }

    private <T extends JvmLibraryArtifact> SoftwareArtifactSet<T> buildJvmLibrarySoftwareArtifactSet(Class<T> type, Class<? extends T> implType, ModuleVersionMetaData component, ArtifactResolver artifactResolver) {
        ArtifactResolveContext context = new ArtifactTypeResolveContext(type);
        BuildableArtifactSetResolveResult artifactSetResolveResult = new DefaultBuildableArtifactSetResolveResult();
        artifactResolver.resolveModuleArtifacts(component, context, artifactSetResolveResult);

        Instantiator instantiator = new DirectInstantiator();
        SoftwareArtifactSet<T> javadocs;
        if (artifactSetResolveResult.getFailure() != null) {
            javadocs = new DefaultSoftwareArtifactSet<T>(artifactSetResolveResult.getFailure());
        } else {
            Set<T> javadocArtifacts = Sets.newHashSet();
            for (ComponentArtifactMetaData artifactMetaData : artifactSetResolveResult.getArtifacts()) {
                BuildableArtifactResolveResult resolveResult = new DefaultBuildableArtifactResolveResult();
                artifactResolver.resolveArtifact(artifactMetaData, component.getSource(), resolveResult);
                if (resolveResult.getFailure() != null) {
                    javadocArtifacts.add(instantiator.newInstance(implType, resolveResult.getFailure()));
                } else {
                    javadocArtifacts.add(instantiator.newInstance(implType, resolveResult.getFile()));
                }
            }
            javadocs = new DefaultSoftwareArtifactSet<T>(javadocArtifacts);
        }
        return javadocs;
    }

    private ModuleRevisionId toModuleRevisionId(ModuleComponentIdentifier componentId) {
        return ModuleRevisionId.newInstance(componentId.getGroup(), componentId.getModule(), componentId.getVersion());
    }
}
