/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.api.internal.project.taskfactory;

import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Task;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.util.ReflectionUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * A {@link ITaskFactory} which determines task actions, inputs and outputs based on annotation attached to the task
 * properties. Also provides some validation based on these annotations.
 */
public class AnnotationProcessingTaskFactory implements ITaskFactory {
    private final ITaskFactory taskFactory;
    private final Map<Class, List<Action<Task>>> actionsForType = new HashMap<Class, List<Action<Task>>>();
    private final List<? extends PropertyAnnotationHandler> handlers = Arrays.asList(
            new InputFilePropertyAnnotationHandler(),
            new InputDirectoryPropertyAnnotationHandler(),
            new InputFilesPropertyAnnotationHandler(),
            new OutputFilePropertyAnnotationHandler(),
            new OutputDirectoryPropertyAnnotationHandler(),
            new InputPropertyAnnotationHandler(),
            new NestedBeanPropertyAnnotationHandler());
    private final ValidationAction notNullValidator = new ValidationAction() {
        public void validate(String propertyName, Object value) throws InvalidUserDataException {
            if (value == null) {
                throw new InvalidUserDataException(String.format("No value has been specified for property '%s'.",
                        propertyName));
            }
        }
    };

    public AnnotationProcessingTaskFactory(ITaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    public TaskInternal createTask(ProjectInternal project, Map<String, ?> args) {
        TaskInternal task = taskFactory.createTask(project, args);

        Class<? extends Task> type = task.getClass();
        List<Action<Task>> actions = actionsForType.get(type);
        if (actions == null) {
            actions = createActionsForType(type);
            actionsForType.put(type, actions);
        }

        for (Action<Task> action : actions) {
            task.doFirst(action);
            if (action instanceof Validator) {
                Validator validator = (Validator) action;
                validator.addInputsAndOutputs(task);
            }
        }

        return task;
    }

    private List<Action<Task>> createActionsForType(Class<? extends Task> type) {
        List<Action<Task>> actions = new ArrayList<Action<Task>>();
        findTaskAction(type, actions);
        findProperties(type, actions);
        return actions;
    }

    private void findProperties(Class<? extends Task> type, List<Action<Task>> actions) {
        Validator validator = new Validator();

        validator.attachActions(null, type);

        if (!validator.properties.isEmpty()) {
            actions.add(validator);
        }
    }

    private void findTaskAction(Class<? extends Task> type, List<Action<Task>> actions) {
        for (Class current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                attachTaskAction(method, actions);
            }
        }
    }

    private void attachTaskAction(final Method method, Collection<Action<Task>> actions) {
        if (method.getAnnotation(TaskAction.class) == null) {
            return;
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new GradleException(String.format("Cannot use @TaskAction annotation on static method %s.%s().",
                    method.getDeclaringClass().getSimpleName(), method.getName()));
        }
        if (method.getParameterTypes().length > 0) {
            throw new GradleException(String.format(
                    "Cannot use @TaskAction annotation on method %s.%s() as this method takes parameters.",
                    method.getDeclaringClass().getSimpleName(), method.getName()));
        }
        actions.add(new Action<Task>() {
            public void execute(Task task) {
                ReflectionUtil.invoke(task, method.getName(), new Object[0]);
            }
        });
    }

    private static boolean isGetter(Method method) {
        return method.getName().startsWith("get") && method.getReturnType() != Void.TYPE
                && method.getParameterTypes().length == 0 && !Modifier.isStatic(method.getModifiers());
    }

    private class Validator implements Action<Task> {
        private Set<PropertyInfo> properties = new LinkedHashSet<PropertyInfo>();

        public void addInputsAndOutputs(final Task task) {
            for (final PropertyInfo property : properties) {
                Callable<Object> futureValue = new Callable<Object>() {
                    public Object call() throws Exception {
                        return property.getValue(task).getValue();
                    }
                };

                property.configureAction.update(task, futureValue);
            }
        }

        public void execute(Task task) {
            try {
                List<PropertyValue> propertyValues = new ArrayList<PropertyValue>();
                for (PropertyInfo property : properties) {
                    propertyValues.add(property.getValue(task));
                }
                for (PropertyValue propertyValue : propertyValues) {
                    propertyValue.checkNotNull();
                }
                for (PropertyValue propertyValue : propertyValues) {
                    propertyValue.checkSkip();
                }
                for (PropertyValue propertyValue : propertyValues) {
                    propertyValue.checkValid();
                }
            } catch (InvalidUserDataException e) {
                throw new InvalidUserDataException(String.format("Error validating %s: %s", task, e.getMessage()), e);
            }
        }

        public void attachActions(PropertyInfo parent, Class<?> type) {
            if (type.getSuperclass() != null) {
                attachActions(parent, type.getSuperclass());
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!isGetter(method)) {
                    continue;
                }

                String fieldName = StringUtils.uncapitalize(method.getName().substring(3));
                String propertyName = fieldName;
                if (parent != null) {
                    propertyName = parent.getName() + '.' + propertyName;
                }
                PropertyInfo propertyInfo = new PropertyInfo(this, parent, propertyName, method);

                attachValidationActions(propertyInfo, fieldName);

                if (propertyInfo.required) {
                    properties.add(propertyInfo);
                }
            }
        }

        private void attachValidationActions(PropertyInfo propertyInfo, String fieldName) {
            for (PropertyAnnotationHandler handler : handlers) {
                attachValidationAction(handler, propertyInfo, fieldName);
            }
        }

        private void attachValidationAction(PropertyAnnotationHandler handler, PropertyInfo propertyInfo, String fieldName) {
            final Method method = propertyInfo.method;
            Class<? extends Annotation> annotationType = handler.getAnnotationType();

            AnnotatedElement annotationTarget = null;
            if (method.getAnnotation(annotationType) != null) {
                annotationTarget = method;
            } else {
                try {
                    Field field = method.getDeclaringClass().getDeclaredField(fieldName);
                    if (field.getAnnotation(annotationType) != null) {
                        annotationTarget = field;
                    }
                } catch (NoSuchFieldException e) {
                    // ok - ignore
                }
            }
            if (annotationTarget == null) {
                return;
            }

            Annotation optional = annotationTarget.getAnnotation(Optional.class);
            if (optional == null) {
                propertyInfo.setNotNullValidator(notNullValidator);
            }

            propertyInfo.attachActions(handler);
        }
    }

    private interface PropertyValue {
        Object getValue();

        void checkNotNull();

        void checkSkip();

        void checkValid();
    }

    private static class PropertyInfo implements PropertyActionContext {
        private static final ValidationAction NO_OP_VALIDATION_ACTION = new ValidationAction() {
            public void validate(String propertyName, Object value) throws InvalidUserDataException {
            }
        };
        private static final PropertyValue NO_OP_VALUE = new PropertyValue() {
            public Object getValue() {
                return null;
            }

            public void checkNotNull() {
            }

            public void checkSkip() {
            }

            public void checkValid() {
            }
        };
        private static final UpdateAction NO_OP_CONFIGURATION_ACTION = new UpdateAction() {
            public void update(Task task, Callable<Object> futureValue) {
            }
        };

        private final Validator validator;
        private final PropertyInfo parent;
        private final String propertyName;
        private final Method method;
        private ValidationAction skipAction = NO_OP_VALIDATION_ACTION;
        private ValidationAction validationAction = NO_OP_VALIDATION_ACTION;
        private ValidationAction notNullValidator = NO_OP_VALIDATION_ACTION;
        private UpdateAction configureAction = NO_OP_CONFIGURATION_ACTION;
        public boolean required;

        private PropertyInfo(Validator validator, PropertyInfo parent, String propertyName, Method method) {
            this.validator = validator;
            this.parent = parent;
            this.propertyName = propertyName;
            this.method = method;
        }

        @Override
        public String toString() {
            return propertyName;
        }

        public String getName() {
            return propertyName;
        }

        public Class<?> getType() {
            return method.getReturnType();
        }

        public AnnotatedElement getTarget() {
            return method;
        }

        public void setSkipAction(ValidationAction action) {
            skipAction = action;
        }

        public void setValidationAction(ValidationAction action) {
            validationAction = action;
        }

        public void setConfigureAction(UpdateAction action) {
            configureAction = action;
        }

        public void setNotNullValidator(ValidationAction notNullValidator) {
            this.notNullValidator = notNullValidator;
        }

        public void attachActions(Class<?> type) {
            validator.attachActions(this, type);
        }

        public PropertyValue getValue(Object rootObject) {
            Object bean = rootObject;
            if (parent != null) {
                PropertyValue parentValue = parent.getValue(rootObject);
                if (parentValue.getValue() == null) {
                    return NO_OP_VALUE;
                }
                bean = parentValue.getValue();
            }

            final Object value = ReflectionUtil.invoke(bean, method.getName(), new Object[0]);

            return new PropertyValue() {
                public Object getValue() {
                    return value;
                }

                public void checkNotNull() {
                    notNullValidator.validate(propertyName, value);
                }

                public void checkSkip() {
                    skipAction.validate(propertyName, value);
                }

                public void checkValid() {
                    if (value != null) {
                        validationAction.validate(propertyName, value);
                    }
                }
            };
        }

        public void attachActions(PropertyAnnotationHandler handler) {
            handler.attachActions(this);
            required = true;
        }
    }
}
