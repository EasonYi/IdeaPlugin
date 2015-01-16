package org.axonframework.intellij.ide.plugin.publisher;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import com.intellij.util.Query;
import com.intellij.util.containers.ConcurrentMultiMap;
import com.intellij.util.containers.MultiMap;

import java.util.Collections;
import java.util.List;

import static java.util.Arrays.asList;

class DefaultEventPublisherProvider implements EventPublisherProvider {

    private final MultiMap<Project, PsiMethod> publisherMethodsPerProject = new ConcurrentMultiMap<Project, PsiMethod>();

    @Override
    public void scanPublishers(Project project, GlobalSearchScope scope, final Registrar registrar) {
        cleanClosedProjects();
        publisherMethodsPerProject.putValues(project, findMethods(project, GlobalSearchScope.allScope(project),
                "org.axonframework.eventsourcing.AbstractEventSourcedAggregateRoot", "apply"));
        publisherMethodsPerProject.putValues(project, findMethods(project, GlobalSearchScope.allScope(project),
                "org.axonframework.domain.AbstractAggregateRoot", "registerEvent"));
        publisherMethodsPerProject.putValues(project, findMethods(project, GlobalSearchScope.allScope(project),
                "org.axonframework.eventsourcing.AbstractEventSourcedEntity", "apply"));

        for (PsiMethod method : publisherMethodsPerProject.values()) {
            Query<PsiReference> invocations = ReferencesSearch.search(method, scope);
            invocations.forEachAsync(new Processor<PsiReference>() {
                @Override
                public boolean process(PsiReference psiReference) {
                    PsiMethodCallExpression methodCall = (PsiMethodCallExpression) PsiTreeUtil.findFirstParent(
                            psiReference.getElement(),
                            new Condition<PsiElement>() {
                                @Override
                                public boolean value(PsiElement psiElement) {
                                    return psiElement instanceof PsiMethodCallExpression;
                                }
                            });
                    if (methodCall != null) {
                        PsiType[] expressionTypes = methodCall.getArgumentList().getExpressionTypes();
                        if (methodCall.getMethodExpression().getReference() != null) {
                            final PsiMethod referencedMethod = (PsiMethod) methodCall.getMethodExpression()
                                    .getReference().resolve();
                            if (referencedMethod != null) {
                                if (expressionTypes.length > 0) {
                                    registrar.registerPublisher(new DefaultEventPublisher(expressionTypes[0], methodCall));
                                }
                            }
                        }
                    }
                    return true;
                }
            });
        }

        Query<PsiClass> search = AllClassesSearch.search(scope, project);
        search.forEach(new Processor<PsiClass>() {
            @Override
            public boolean process(final PsiClass psiClass) {
                PsiMethod[] constructors = psiClass.getConstructors();
                for (PsiMethod constructor : constructors) {
//                    System.out.println("Constructor: " + constructor);
                    Query<PsiReference> psiReferences = MethodReferencesSearch.search(constructor);
                    psiReferences.forEach(new Processor<PsiReference>() {
                        @Override
                        public boolean process(PsiReference psiReference) {
//                            System.out.println("Constructor invocations: " + psiReference.getElement());
                            registrar.registerPublisher(new CommandEventPublisher(psiClass, psiReference.getElement()));
                            return true;
                        }
                    });


                }
                return true;
            }
        });
    }

    private void cleanClosedProjects() {
        for (Project project : publisherMethodsPerProject.keySet()) {
            if (!project.isOpen()) {
                publisherMethodsPerProject.remove(project);
            }
        }
    }

    private List<PsiMethod> findMethods(Project project, GlobalSearchScope allScope, String className,
                                        String methodName) {
        PsiClass aggregateClass = JavaPsiFacade.getInstance(project).findClass(className, allScope);
        if (aggregateClass != null) {
            return asList(aggregateClass.findMethodsByName(methodName, true));
        }
        return Collections.emptyList();
    }

    @Override
    public EventPublisher resolve(PsiElement element) {
        if (element instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression expression = (PsiMethodCallExpression) element;
            PsiType[] expressionTypes = expression.getArgumentList().getExpressionTypes();
            for (PsiMethod method : publisherMethodsPerProject.get(element.getProject())) {
                if (expression.getMethodExpression().getReference() != null
                        && expression.getMethodExpression().isReferenceTo(method)) {
                    return new DefaultEventPublisher(expressionTypes[0], element);
                }
            }
        }
        return null;
    }
}
