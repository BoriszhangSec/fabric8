/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.forge.camel.commands.project;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.JSonSchemaHelper;
import org.jboss.forge.addon.dependencies.Dependency;
import org.jboss.forge.addon.dependencies.builder.DependencyBuilder;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.dependencies.DependencyInstaller;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;
import org.jboss.forge.roaster.model.util.Strings;

public final class CamelCommands {

    // to speed up performance on command line completion lets not perform a full classpath validation of the project until its being used on a command
    private static final boolean validateClassPathForProjectValidation = false;

    public static Iterable<String> createComponentNameValues(Project project) {
        return new CamelComponentsLabelCompleter(project).getValueChoices();
    }

    public static Callable<Iterable<String>> createComponentNameValues(final Project project,
                                                                       final UISelectOne<String> componentCategoryFilter,
                                                                       final boolean excludeComponentsOnClasspath) {
        // use callable so we can live update the filter
        return new Callable<Iterable<String>>() {
            @Override
            public Iterable<String> call() throws Exception {
                String label = componentCategoryFilter.getValue();
                return new CamelComponentsCompleter(project, null, excludeComponentsOnClasspath).getValueChoices(label);
            }
        };
    }

    /**
     * Populates the details for the given component, returning a Result if it fails.
     */
    public static Result loadCamelComponentDetails(String camelComponentName, CamelComponentDetails details) {
        CamelCatalog catalog = new DefaultCamelCatalog();
        String json = catalog.componentJSonSchema(camelComponentName);
        if (json == null) {
            return Results.fail("Could not find catalog entry for component name: " + camelComponentName);
        }
        List<Map<String, String>> data = JSonSchemaHelper.parseJsonSchema("component", json, false);

        for (Map<String, String> row : data) {
            String javaType = row.get("javaType");
            if (!Strings.isNullOrEmpty(javaType)) {
                details.setComponentClassQName(javaType);
            }
            String artifactId = row.get("artifactId");
            if (!Strings.isNullOrEmpty(artifactId)) {
                details.setArtifactId(artifactId);
            }
        }
        if (Strings.isNullOrEmpty(details.getComponentClassQName())) {
            return Results.fail("Could not find fully qualified class name in catalog for component name: " + camelComponentName);
        }
        return null;
    }

    public static Result ensureCamelArtifactIdAdded(Project project, CamelComponentDetails details, DependencyInstaller dependencyInstaller) {
        String artifactId = details.getArtifactId();
        Dependency core = CamelProjectHelper.findCamelCoreDependency(project);
        if (core == null) {
            return Results.fail("The project does not include camel-core");
        }

        DependencyBuilder component = DependencyBuilder.create().setGroupId("org.apache.camel")
                .setArtifactId(artifactId).setVersion(core.getCoordinate().getVersion());

        // install the component
        dependencyInstaller.install(project, component);
        return null;
    }

    public static boolean isCdiProject(Project project) {
        return (!validateClassPathForProjectValidation || JavaHelper.projectHasClassOnClassPath(project, "javax.enterprise.inject.Produces")) &&
                CamelProjectHelper.findCamelCDIDependency(project) != null;
    }

    public static boolean isSpringProject(Project project) {
        return (!validateClassPathForProjectValidation || JavaHelper.projectHasClassOnClassPath(project, "org.springframework.context.ApplicationContext")) &&
                CamelProjectHelper.findCamelSpringDependency(project) != null;
    }

    public static boolean isBlueprintProject(Project project) {
        return CamelProjectHelper.findCamelBlueprintDependency(project) != null;
    }

    protected static void createCdiComponentProducerClass(JavaClassSource javaClass, CamelComponentDetails details, String camelComponentName, String componentInstanceName, String configurationCode) {
        javaClass.addImport("javax.enterprise.inject.Produces");
        javaClass.addImport("javax.inject.Singleton");
        javaClass.addImport("javax.inject.Named");
        javaClass.addImport(details.getComponentClassQName());

        String componentClassName = details.getComponentClassName();
        String methodName = "create" + Strings.capitalize(componentInstanceName) + "Component";

        String body = componentClassName + " component = new " + componentClassName + "();" + configurationCode + "\nreturn component;";

        MethodSource<JavaClassSource> method = javaClass.addMethod()
                .setPublic()
                .setReturnType(componentClassName)
                .setName(methodName)
                .setBody(body)
                .addThrows(Exception.class);

        method.addAnnotation("Named").setStringValue(camelComponentName);
        method.addAnnotation("Produces");
        method.addAnnotation("Singleton");
    }

    protected static void createSpringComponentFactoryClass(JavaClassSource javaClass, CamelComponentDetails details, String camelComponentName, String componentInstanceName, String configurationCode) {
        javaClass.addAnnotation("Component");

        javaClass.addImport("org.springframework.beans.factory.config.BeanDefinition");
        javaClass.addImport("org.springframework.beans.factory.annotation.Qualifier");
        javaClass.addImport("org.springframework.context.annotation.Bean");
        javaClass.addImport("org.springframework.context.annotation.Scope");
        javaClass.addImport("org.springframework.stereotype.Component");
        javaClass.addImport(details.getComponentClassQName());

        String componentClassName = details.getComponentClassName();
        String methodName = "create" + Strings.capitalize(componentInstanceName) + "Component";

        String body = componentClassName + " component = new " + componentClassName + "();" + configurationCode + "\nreturn component;";

        MethodSource<JavaClassSource> method = javaClass.addMethod()
                .setPublic()
                .setReturnType(componentClassName)
                .setName(methodName)
                .setBody(body)
                .addThrows(Exception.class);

        method.addAnnotation("Qualifier").setStringValue(camelComponentName);
        method.addAnnotation("Bean");
        method.addAnnotation("Scope").setLiteralValue("BeanDefinition.SCOPE_SINGLETON");
    }

    /**
     * Converts a java type as a string to a valid input type and returns the class or null if its not supported
     */
    protected static Class<Object> loadValidInputTypes(String javaType, String type) {
        try {
            Class<Object> clazz = loadPrimitiveType(javaType);
            if (clazz == null) {
                clazz = (Class<Object>) Class.forName(javaType);
            }
            if (clazz.equals(String.class) || clazz.equals(Date.class)
                    || clazz.isPrimitive() || Number.class.isAssignableFrom(clazz)) {
                return clazz;
            }
        } catch (ClassNotFoundException e) {
            // ignore errors
        }
        return null;
    }

    private static Class loadPrimitiveType(String name) {
        // special for byte[] or Object[] as its common to use
        if ("java.lang.byte[]".equals(name) || "byte[]".equals(name)) {
            return byte[].class;
        } else if ("java.lang.Byte[]".equals(name) || "Byte[]".equals(name)) {
            return Byte[].class;
        } else if ("java.lang.Object[]".equals(name) || "Object[]".equals(name)) {
            return Object[].class;
        } else if ("java.lang.String[]".equals(name) || "String[]".equals(name)) {
            return String[].class;
            // and these is common as well
        } else if ("java.lang.String".equals(name) || "String".equals(name)) {
            return String.class;
        } else if ("java.lang.Boolean".equals(name) || "Boolean".equals(name)) {
            return Boolean.class;
        } else if ("boolean".equals(name)) {
            return boolean.class;
        } else if ("java.lang.Integer".equals(name) || "Integer".equals(name)) {
            return Integer.class;
        } else if ("int".equals(name)) {
            return int.class;
        } else if ("java.lang.Long".equals(name) || "Long".equals(name)) {
            return Long.class;
        } else if ("long".equals(name)) {
            return long.class;
        } else if ("java.lang.Short".equals(name) || "Short".equals(name)) {
            return Short.class;
        } else if ("short".equals(name)) {
            return short.class;
        } else if ("java.lang.Byte".equals(name) || "Byte".equals(name)) {
            return Byte.class;
        } else if ("byte".equals(name)) {
            return byte.class;
        } else if ("java.lang.Float".equals(name) || "Float".equals(name)) {
            return Float.class;
        } else if ("float".equals(name)) {
            return float.class;
        } else if ("java.lang.Double".equals(name) || "Double".equals(name)) {
            return Double.class;
        } else if ("double".equals(name)) {
            return double.class;
        } else if ("java.lang.Character".equals(name) || "Character".equals(name)) {
            return Character.class;
        } else if ("char".equals(name)) {
            return char.class;
        }
        return null;
    }
}
