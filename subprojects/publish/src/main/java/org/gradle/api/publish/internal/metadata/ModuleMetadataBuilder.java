/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.api.publish.internal.metadata;

import com.google.common.collect.Sets;
import org.gradle.api.Named;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.component.ComponentWithCoordinates;
import org.gradle.api.component.ComponentWithVariants;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.internal.artifacts.DefaultExcludeRule;
import org.gradle.api.internal.artifacts.ImmutableVersionConstraint;
import org.gradle.api.internal.artifacts.PublishArtifactInternal;
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint;
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependencyConstraint;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.component.SoftwareComponentInternal;
import org.gradle.api.internal.component.UsageContext;
import org.gradle.api.publish.internal.PublicationInternal;
import org.gradle.api.publish.internal.versionmapping.VariantVersionMappingStrategyInternal;
import org.gradle.api.publish.internal.versionmapping.VersionMappingStrategyInternal;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static org.apache.commons.lang.StringUtils.isNotEmpty;

class ModuleMetadataBuilder {

    private final PublicationInternal<?> publication;
    private final ModuleVersionIdentifier publicationCoordinates;
    private final SoftwareComponentInternal component;
    private final Collection<? extends PublicationInternal<?>> publications;
    private final Map<SoftwareComponent, ComponentData> componentCoordinates = new HashMap<>();
    private final ProjectDependencyPublicationResolver projectDependencyResolver;
    private final InvalidPublicationChecker checker;

    public ModuleMetadataBuilder(
        PublicationInternal<?> publication,
        Collection<? extends PublicationInternal<?>> publications,
        InvalidPublicationChecker checker,
        ProjectDependencyPublicationResolver projectDependencyResolver
    ) {
        this.component = publication.getComponent();
        this.publicationCoordinates = publication.getCoordinates();
        this.publication = publication;
        this.publications = publications;
        this.checker = checker;
        this.projectDependencyResolver = projectDependencyResolver;
        // Collect a map from component to coordinates. This might be better to move to the component or some publications model
        collectCoordinates(componentCoordinates);
    }

    ModuleMetadata build() {
        return new ModuleMetadata(identity(), variants());
    }

    private ModuleMetadata.Identity identity() {
        // Collect a map from component to its owning component. This might be better to move to the component or some publications model
        Map<SoftwareComponent, SoftwareComponent> owners = new HashMap<>();
        collectOwners(publications, owners);

        SoftwareComponent owner = owners.get(component);
        ComponentData ownerData = owner == null ? null : componentCoordinates.get(owner);
        ComponentData componentData = componentCoordinates.get(component);

        return ownerData != null
            ? identityFor(ownerData, relativeUrlTo(componentData.coordinates, ownerData.coordinates))
            : identityFor(componentData, null);
    }

    private ModuleMetadata.Identity identityFor(ComponentData componentData, String relativeUrl) {
        return new ModuleMetadata.Identity(
            componentData.coordinates,
            attributesFor(componentData.attributes),
            relativeUrl
        );
    }

    private List<ModuleMetadata.Variant> variants() {
        ArrayList<ModuleMetadata.Variant> variants = new ArrayList<>();
        for (UsageContext variant : component.getUsages()) {
            checkVariant(variant);
            variants.add(
                new ModuleMetadata.LocalVariant(
                    variant.getName(),
                    attributesFor(variant.getAttributes()),
                    capabilitiesFor(variant.getCapabilities()),
                    dependenciesOf(variant),
                    dependencyConstraintsFor(variant),
                    artifactsOf(variant)
                )
            );
        }
        if (component instanceof ComponentWithVariants) {
            for (SoftwareComponent childComponent : ((ComponentWithVariants) component).getVariants()) {
                ModuleVersionIdentifier childCoordinates = coordinatesOf(childComponent);
                assert childCoordinates != null;
                if (childComponent instanceof SoftwareComponentInternal) {
                    for (UsageContext variant : ((SoftwareComponentInternal) childComponent).getUsages()) {
                        checkVariant(variant);
                        variants.add(
                            new ModuleMetadata.RemoteVariant(
                                variant.getName(),
                                attributesFor(variant.getAttributes()),
                                availableAt(publicationCoordinates, childCoordinates),
                                capabilitiesFor(variant.getCapabilities())
                            )
                        );
                    }
                }
            }
        }
        return variants;
    }

    private List<ModuleMetadata.Artifact> artifactsOf(UsageContext variant) {
        if (variant.getArtifacts().isEmpty()) {
            return emptyList();
        }
        ArrayList<ModuleMetadata.Artifact> artifacts = new ArrayList<>();
        for (PublishArtifact artifact : variant.getArtifacts()) {
            ModuleMetadata.Artifact metadataArtifact = artifactFor(artifact);
            if (metadataArtifact != null) {
                artifacts.add(metadataArtifact);
            }
        }
        return artifacts;
    }

    @Nullable
    private ModuleMetadata.Artifact artifactFor(PublishArtifact artifact) {
        if (shouldNotBePublished(artifact)) {
            return null;
        }
        PublicationInternal.PublishedFile publishedFile = publication.getPublishedFile(artifact);
        return new ModuleMetadata.Artifact(
            publishedFile.getName(),
            publishedFile.getUri(),
            artifact.getFile()
        );
    }

    private boolean shouldNotBePublished(PublishArtifact artifact) {
        return artifact instanceof PublishArtifactInternal
            && !((PublishArtifactInternal) artifact).shouldBePublished();
    }

    private ModuleMetadata.AvailableAt availableAt(ModuleVersionIdentifier coordinates, ModuleVersionIdentifier targetCoordinates) {
        return new ModuleMetadata.AvailableAt(
            relativeUrlTo(coordinates, targetCoordinates),
            targetCoordinates
        );
    }

    private ModuleMetadata.Dependency dependencyFor(
        ModuleDependency dependency,
        Set<ExcludeRule> additionalExcludes,
        VariantVersionMappingStrategyInternal versionMappingStrategy,
        DependencyArtifact dependencyArtifact
    ) {
        return new ModuleMetadata.Dependency(
            dependencyCoordinatesFor(dependency, versionMappingStrategy),
            excludedRulesFor(dependency, additionalExcludes),
            attributesFor(dependency.getAttributes()),
            capabilitiesFor(dependency.getRequestedCapabilities()),
            dependency.isEndorsingStrictVersions(),
            isNotEmpty(dependency.getReason()) ? dependency.getReason() : null,
            dependencyArtifact != null ? artifactSelectorFor(dependencyArtifact) : null
        );
    }

    private ModuleMetadata.DependencyCoordinates dependencyCoordinatesFor(
        ModuleDependency dependency,
        VariantVersionMappingStrategyInternal versionMappingStrategy
    ) {
        return dependency instanceof ProjectDependency
            ? projectDependencyCoordinatesFor((ProjectDependency) dependency, versionMappingStrategy)
            : moduleDependencyCoordinatesFor(dependency, versionMappingStrategy);
    }

    private ModuleMetadata.ArtifactSelector artifactSelectorFor(DependencyArtifact dependencyArtifact) {
        return new ModuleMetadata.ArtifactSelector(
            dependencyArtifact.getName(),
            dependencyArtifact.getType(),
            isNullOrEmpty(dependencyArtifact.getExtension()) ? null : dependencyArtifact.getExtension(),
            isNullOrEmpty(dependencyArtifact.getClassifier()) ? null : dependencyArtifact.getClassifier()
        );
    }

    private List<ModuleMetadata.Capability> capabilitiesFor(Collection<? extends Capability> capabilities) {
        if (capabilities.isEmpty()) {
            return emptyList();
        }

        ArrayList<ModuleMetadata.Capability> metadataCapabilities = new ArrayList<>();
        for (Capability capability : capabilities) {
            metadataCapabilities.add(
                new ModuleMetadata.Capability(
                    capability.getGroup(),
                    capability.getName(),
                    isNotEmpty(capability.getVersion()) ? capability.getVersion() : null
                )
            );
        }
        return metadataCapabilities;
    }

    private List<ModuleMetadata.Attribute> attributesFor(AttributeContainer attributes) {
        if (attributes.isEmpty()) {
            return emptyList();
        }

        ArrayList<ModuleMetadata.Attribute> metadataAttributes = new ArrayList<>();
        for (Attribute<?> attribute : sorted(attributes).values()) {
            String name = attribute.getName();
            Object value = attributes.getAttribute(attribute);
            Object effectiveValue = attributeValueFor(value);
            if (effectiveValue == null) {
                throw new IllegalArgumentException(
                    format("Cannot write attribute %s with unsupported value %s of type %s.", name, value, value.getClass().getName())
                );
            }
            metadataAttributes.add(
                new ModuleMetadata.Attribute(name, effectiveValue)
            );
        }
        return metadataAttributes;
    }

    private Object attributeValueFor(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof String) {
            return value;
        } else if (value instanceof Named) {
            return ((Named) value).getName();
        } else if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        } else {
            return null;
        }
    }

    private ModuleMetadata.DependencyCoordinates moduleDependencyCoordinatesFor(
        ModuleDependency dependency,
        VariantVersionMappingStrategyInternal versionMappingStrategy
    ) {
        String group = dependency.getGroup();
        String name = dependency.getName();
        String resolvedVersion = null;
        if (versionMappingStrategy != null) {
            ModuleVersionIdentifier resolvedVersionId = versionMappingStrategy.maybeResolveVersion(group, name);
            if (resolvedVersionId != null) {
                group = resolvedVersionId.getGroup();
                name = resolvedVersionId.getName();
                resolvedVersion = resolvedVersionId.getVersion();
            }
        }
        return new ModuleMetadata.DependencyCoordinates(
            group,
            name,
            versionFor(versionConstraintFor(dependency), resolvedVersion)
        );
    }

    private ModuleMetadata.DependencyCoordinates projectDependencyCoordinatesFor(
        ProjectDependency projectDependency,
        VariantVersionMappingStrategyInternal versionMappingStrategy
    ) {
        String resolvedVersion = null;
        ModuleVersionIdentifier identifier = moduleIdentifierFor(projectDependency);
        if (versionMappingStrategy != null) {
            ModuleVersionIdentifier resolved =
                versionMappingStrategy.maybeResolveVersion(
                    identifier.getGroup(),
                    identifier.getName()
                );
            if (resolved != null) {
                identifier = resolved;
                resolvedVersion = identifier.getVersion();
            }
        }
        return new ModuleMetadata.DependencyCoordinates(
            identifier.getGroup(),
            identifier.getName(),
            versionFor(
                DefaultImmutableVersionConstraint.of(identifier.getVersion()),
                resolvedVersion
            )
        );
    }

    private List<ModuleMetadata.Dependency> dependenciesOf(UsageContext variant) {
        if (variant.getDependencies().isEmpty()) {
            return emptyList();
        }
        ArrayList<ModuleMetadata.Dependency> dependencies = new ArrayList<>();
        Set<ExcludeRule> additionalExcludes = variant.getGlobalExcludes();
        VariantVersionMappingStrategyInternal versionMappingStrategy = versionMappingStrategyFor(variant);
        for (ModuleDependency moduleDependency : variant.getDependencies()) {
            if (moduleDependency.getArtifacts().isEmpty()) {
                dependencies.add(
                    dependencyFor(
                        moduleDependency,
                        additionalExcludes,
                        versionMappingStrategy,
                        null
                    )
                );
            } else {
                for (DependencyArtifact dependencyArtifact : moduleDependency.getArtifacts()) {
                    dependencies.add(
                        dependencyFor(
                            moduleDependency,
                            additionalExcludes,
                            versionMappingStrategy,
                            dependencyArtifact
                        )
                    );
                }
            }
        }
        return dependencies;
    }

    private List<ModuleMetadata.DependencyConstraint> dependencyConstraintsFor(UsageContext variant) {
        if (variant.getDependencyConstraints().isEmpty()) {
            return emptyList();
        }
        VariantVersionMappingStrategyInternal versionMappingStrategy = versionMappingStrategyFor(variant);
        ArrayList<ModuleMetadata.DependencyConstraint> dependencyConstraints = new ArrayList<>();
        for (DependencyConstraint dependencyConstraint : variant.getDependencyConstraints()) {
            dependencyConstraints.add(
                dependencyConstraintFor(dependencyConstraint, versionMappingStrategy)
            );
        }
        return dependencyConstraints;
    }

    private ModuleMetadata.DependencyConstraint dependencyConstraintFor(DependencyConstraint dependencyConstraint, VariantVersionMappingStrategyInternal variantVersionMappingStrategy) {
        String group;
        String module;
        String resolvedVersion = null;
        if (dependencyConstraint instanceof DefaultProjectDependencyConstraint) {
            DefaultProjectDependencyConstraint dependency = (DefaultProjectDependencyConstraint) dependencyConstraint;
            ProjectDependency projectDependency = dependency.getProjectDependency();
            ModuleVersionIdentifier identifier = moduleIdentifierFor(projectDependency);
            group = identifier.getGroup();
            module = identifier.getName();
            resolvedVersion = identifier.getVersion();
        } else {
            group = dependencyConstraint.getGroup();
            module = dependencyConstraint.getName();
        }
        ModuleVersionIdentifier resolvedVersionId = variantVersionMappingStrategy != null
            ? variantVersionMappingStrategy.maybeResolveVersion(group, module)
            : null;
        String effectiveGroup = resolvedVersionId != null ? resolvedVersionId.getGroup() : group;
        String effectiveModule = resolvedVersionId != null ? resolvedVersionId.getName() : module;
        String effectiveVersion = resolvedVersionId != null ? resolvedVersionId.getVersion() : resolvedVersion;
        return new ModuleMetadata.DependencyConstraint(
            effectiveGroup,
            effectiveModule,
            versionFor(
                DefaultImmutableVersionConstraint.of(dependencyConstraint.getVersionConstraint()),
                effectiveVersion
            ),
            attributesFor(dependencyConstraint.getAttributes()),
            isNotEmpty(dependencyConstraint.getReason()) ? dependencyConstraint.getReason() : null
        );
    }

    @Nullable
    private ModuleMetadata.Version versionFor(
        ImmutableVersionConstraint versionConstraint,
        @Nullable String resolvedVersion
    ) {
        checker.sawDependencyOrConstraint();
        if (resolvedVersion == null && isEmpty(versionConstraint)) {
            return null;
        }
        checker.sawVersion();

        boolean isStrict = !versionConstraint.getStrictVersion().isEmpty();
        String version;
        String preferred;
        if (resolvedVersion != null) {
            version = resolvedVersion;
            preferred = null;
        } else {
            version = isStrict
                ? versionConstraint.getStrictVersion()
                : !versionConstraint.getRequiredVersion().isEmpty()
                ? versionConstraint.getRequiredVersion()
                : null;
            preferred = !versionConstraint.getPreferredVersion().isEmpty()
                ? versionConstraint.getPreferredVersion()
                : null;
        }
        return new ModuleMetadata.Version(
            version,
            isStrict ? version : null,
            preferred,
            versionConstraint.getRejectedVersions()
        );
    }

    private void collectOwners(
        Collection<? extends PublicationInternal<?>> publications,
        Map<SoftwareComponent, SoftwareComponent> owners
    ) {
        for (PublicationInternal<?> publication : publications) {
            if (publication.getComponent() instanceof ComponentWithVariants) {
                ComponentWithVariants componentWithVariants = (ComponentWithVariants) publication.getComponent();
                for (SoftwareComponent child : componentWithVariants.getVariants()) {
                    owners.put(child, publication.getComponent());
                }
            }
        }
    }

    private void collectCoordinates(Map<SoftwareComponent, ComponentData> coordinates) {
        for (PublicationInternal<?> publication : publications) {
            SoftwareComponentInternal component = publication.getComponent();
            if (component != null) {
                coordinates.put(
                    component,
                    new ComponentData(publication.getCoordinates(), publication.getAttributes())
                );
            }
        }
    }

    private void checkVariant(UsageContext usageContext) {
        checker.registerVariant(
            usageContext.getName(),
            usageContext.getAttributes(),
            usageContext.getCapabilities()
        );
    }

    private ImmutableVersionConstraint versionConstraintFor(ModuleDependency dependency) {
        return dependency instanceof ExternalDependency
            ? DefaultImmutableVersionConstraint.of(((ExternalDependency) dependency).getVersionConstraint())
            : DefaultImmutableVersionConstraint.of(nullToEmpty(dependency.getVersion()));
    }

    private Set<ExcludeRule> excludedRulesFor(ModuleDependency moduleDependency, Set<ExcludeRule> additionalExcludes) {
        return moduleDependency.isTransitive()
            ? Sets.union(additionalExcludes, moduleDependency.getExcludeRules())
            : Collections.singleton(new DefaultExcludeRule(null, null));
    }

    private Map<String, Attribute<?>> sorted(AttributeContainer attributes) {
        Map<String, Attribute<?>> sortedAttributes = new TreeMap<>();
        for (Attribute<?> attribute : attributes.keySet()) {
            sortedAttributes.put(attribute.getName(), attribute);
        }
        return sortedAttributes;
    }

    private ModuleVersionIdentifier coordinatesOf(SoftwareComponent childComponent) {
        if (childComponent instanceof ComponentWithCoordinates) {
            return ((ComponentWithCoordinates) childComponent).getCoordinates();
        }
        ComponentData componentData = componentCoordinates.get(childComponent);
        if (componentData != null) {
            return componentData.coordinates;
        }
        return null;
    }

    private ModuleVersionIdentifier moduleIdentifierFor(ProjectDependency projectDependency) {
        return projectDependencyResolver.resolve(ModuleVersionIdentifier.class, projectDependency);
    }

    private VariantVersionMappingStrategyInternal versionMappingStrategyFor(UsageContext variant) {
        VersionMappingStrategyInternal versionMappingStrategy = publication.getVersionMappingStrategy();
        return versionMappingStrategy != null
            ? versionMappingStrategy.findStrategyForVariant(immutableAttributesOf(variant))
            : null;
    }

    private ImmutableAttributes immutableAttributesOf(UsageContext variant) {
        return ((AttributeContainerInternal) variant.getAttributes()).asImmutable();
    }

    private boolean isEmpty(ImmutableVersionConstraint versionConstraint) {
        return DefaultImmutableVersionConstraint.of().equals(versionConstraint);
    }

    public static String relativeUrlTo(
        @SuppressWarnings("unused") ModuleVersionIdentifier from,
        ModuleVersionIdentifier to
    ) {
        // TODO - do not assume Maven layout
        StringBuilder path = new StringBuilder();
        path.append("../../");
        path.append(to.getName());
        path.append("/");
        path.append(to.getVersion());
        path.append("/");
        path.append(to.getName());
        path.append("-");
        path.append(to.getVersion());
        path.append(".module");
        return path.toString();
    }
}
