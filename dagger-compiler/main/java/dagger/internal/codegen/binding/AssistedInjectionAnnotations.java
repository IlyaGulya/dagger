/*
 * Copyright (C) 2020 The Dagger Authors.
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

package dagger.internal.codegen.binding;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static dagger.internal.codegen.extension.DaggerStreams.toImmutableSet;
import static androidx.room.compiler.processing.compat.XConverters.toKS;
import static dagger.internal.codegen.xprocessing.XElements.asConstructor;
import static dagger.internal.codegen.xprocessing.XElements.asTypeElement;

import androidx.room.compiler.codegen.XParameterSpec;
import androidx.room.compiler.processing.XConstructorElement;
import androidx.room.compiler.processing.XConstructorType;
import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XExecutableParameterElement;
import androidx.room.compiler.processing.XHasModifiers;
import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XMethodType;
import androidx.room.compiler.processing.XType;
import androidx.room.compiler.processing.XTypeElement;
import androidx.room.compiler.processing.XVariableElement;
import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.TypeName;
import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;
import dagger.internal.codegen.model.BindingKind;
import dagger.internal.codegen.xprocessing.XParameterSpecs;
import dagger.internal.codegen.xprocessing.XTypeElements;
import dagger.internal.codegen.xprocessing.XTypeNames;
import dagger.internal.codegen.xprocessing.XTypes;
import java.util.List;
import java.util.Optional;

/** Assisted injection utility methods. */
public final class AssistedInjectionAnnotations {
  
  /**
   * System property to control KSP duplicate method workaround.
   * When set to "true", disables the workaround for KSP duplicate method elements.
   * Default is "false" (workaround enabled).
   */
  private static boolean isKspWorkaroundDisabled() {
    return Boolean.parseBoolean(System.getProperty("dagger.ksp.workaround.disabled", "false"));
  }
  /** Returns the factory method for the given factory {@link XTypeElement}. */
  public static XMethodElement assistedFactoryMethod(XTypeElement factory) {
    ImmutableSet<XMethodElement> methods = assistedFactoryMethods(factory);

    // Defensive handling for KSP incremental processing bug (issues #4054, #4063)
    // where getAllNonPrivateInstanceMethods may return duplicate method elements
    if (methods.size() != 1) {
      if (!isKspWorkaroundDisabled()) {
        logDuplicateMethodDiagnostics(factory, methods);

        if (methods.isEmpty()) {
          throw new IllegalArgumentException(
              "Factory " + factory.getQualifiedName() + " has no assisted factory methods. "
              + "This should have been caught during validation.");
        }

        // Recovery: return the first method (Set iteration is deterministic)
        // This works because validation already confirmed there should be exactly one method
        return methods.iterator().next();
      } else {
        // Workaround disabled - throw exception for any count != 1
        if (methods.isEmpty()) {
          throw new IllegalArgumentException(
              "Factory " + factory.getQualifiedName() + " has no assisted factory methods. "
              + "This should have been caught during validation.");
        } else {
          throw new IllegalArgumentException(
              "Factory " + factory.getQualifiedName() + " has " + methods.size() 
              + " assisted factory methods but expected exactly 1. "
              + "KSP workaround is disabled via dagger.ksp.workaround.disabled=true. "
              + "This should have been caught during validation.");
        }
      }
    }

    return getOnlyElement(methods);
  }

  /** Returns the list of abstract factory methods for the given factory {@link XTypeElement}. */
  public static ImmutableSet<XMethodElement> assistedFactoryMethods(XTypeElement factory) {
    return XTypeElements.getAllNonPrivateInstanceMethods(factory).stream()
        .filter(XHasModifiers::isAbstract)
        .filter(method -> !method.isJavaDefault())
        .collect(toImmutableSet());
  }

  /** Returns {@code true} if the element uses assisted injection. */
  public static boolean isAssistedInjectionType(XTypeElement typeElement) {
    return assistedInjectedConstructors(typeElement).stream()
        .anyMatch(constructor -> constructor.hasAnnotation(XTypeNames.ASSISTED_INJECT));
  }

  /** Returns {@code true} if this binding is an assisted factory. */
  public static boolean isAssistedFactoryType(XElement element) {
    return element.hasAnnotation(XTypeNames.ASSISTED_FACTORY);
  }

  /**
   * Returns the list of assisted parameters as {@link XParameterSpec}s.
   *
   * <p>The type of each parameter will be the resolved type given by the binding key, and the name
   * of each parameter will be the name given in the {@link
   * dagger.assisted.AssistedInject}-annotated constructor.
   */
  public static ImmutableList<XParameterSpec> assistedParameterSpecs(Binding binding) {
    checkArgument(binding.kind() == BindingKind.ASSISTED_INJECTION);
    XConstructorElement constructor = asConstructor(binding.bindingElement().get());
    XConstructorType constructorType = constructor.asMemberOf(binding.key().type().xprocessing());
    return assistedParameterSpecs(constructor.getParameters(), constructorType.getParameterTypes());
  }

  private static ImmutableList<XParameterSpec> assistedParameterSpecs(
      List<? extends XExecutableParameterElement> paramElements, List<XType> paramTypes) {
    ImmutableList.Builder<XParameterSpec> assistedParameterSpecs = ImmutableList.builder();
    for (int i = 0; i < paramElements.size(); i++) {
      XExecutableParameterElement paramElement = paramElements.get(i);
      XType paramType = paramTypes.get(i);
      if (isAssistedParameter(paramElement)) {
        assistedParameterSpecs.add(
            XParameterSpecs.of(paramElement.getJvmName(), paramType.asTypeName()));
      }
    }
    return assistedParameterSpecs.build();
  }

  /**
   * Returns the list of assisted factory parameters as {@link XParameterSpec}s.
   *
   * <p>The type of each parameter will be the resolved type given by the binding key, and the name
   * of each parameter will be the name given in the {@link
   * dagger.assisted.AssistedInject}-annotated constructor.
   */
  public static ImmutableList<XParameterSpec> assistedFactoryParameterSpecs(Binding binding) {
    checkArgument(binding.kind() == BindingKind.ASSISTED_FACTORY);

    XTypeElement factory = asTypeElement(binding.bindingElement().get());
    AssistedFactoryMetadata metadata = AssistedFactoryMetadata.create(factory.getType());
    XMethodType factoryMethodType =
        metadata.factoryMethod().asMemberOf(binding.key().type().xprocessing());
    return assistedParameterSpecs(
        // Use the order of the parameters from the @AssistedFactory method but use the parameter
        // names of the @AssistedInject constructor.
        metadata.assistedFactoryAssistedParameters().stream()
            .map(metadata.assistedInjectAssistedParametersMap()::get)
            .collect(toImmutableList()),
        factoryMethodType.getParameterTypes());
  }

  /** Returns the constructors in {@code type} that are annotated with {@link AssistedInject}. */
  public static ImmutableSet<XConstructorElement> assistedInjectedConstructors(XTypeElement type) {
    return type.getConstructors().stream()
        .filter(constructor -> constructor.hasAnnotation(XTypeNames.ASSISTED_INJECT))
        .collect(toImmutableSet());
  }

  public static ImmutableList<XExecutableParameterElement> assistedParameters(Binding binding) {
    return binding.kind() == BindingKind.ASSISTED_INJECTION
        ? asConstructor(binding.bindingElement().get()).getParameters().stream()
            .filter(AssistedInjectionAnnotations::isAssistedParameter)
            .collect(toImmutableList())
        : ImmutableList.of();
  }

  /** Returns {@code true} if this binding is uses assisted injection. */
  public static boolean isAssistedParameter(XVariableElement param) {
    return param.hasAnnotation(XTypeNames.ASSISTED);
  }

  /** Metadata about an {@link dagger.assisted.AssistedFactory} annotated type. */
  @AutoValue
  public abstract static class AssistedFactoryMetadata {
    public static AssistedFactoryMetadata create(XType factoryType) {
      XTypeElement factoryElement = factoryType.getTypeElement();
      XMethodElement factoryMethod = assistedFactoryMethod(factoryElement);
      XMethodType factoryMethodType = factoryMethod.asMemberOf(factoryType);
      XType assistedInjectType = factoryMethodType.getReturnType();
      XTypeElement assistedInjectElement = assistedInjectType.getTypeElement();
      return new AutoValue_AssistedInjectionAnnotations_AssistedFactoryMetadata(
          factoryElement,
          factoryType,
          factoryMethod,
          factoryMethodType,
          assistedInjectElement,
          assistedInjectType,
          AssistedInjectionAnnotations.assistedInjectAssistedParameters(assistedInjectType),
          AssistedInjectionAnnotations.assistedFactoryAssistedParameters(
              factoryMethod, factoryMethodType));
    }

    public abstract XTypeElement factory();

    public abstract XType factoryType();

    public abstract XMethodElement factoryMethod();

    public abstract XMethodType factoryMethodType();

    public abstract XTypeElement assistedInjectElement();

    public abstract XType assistedInjectType();

    public abstract ImmutableList<AssistedParameter> assistedInjectAssistedParameters();

    public abstract ImmutableList<AssistedParameter> assistedFactoryAssistedParameters();

    @Memoized
    public ImmutableMap<AssistedParameter, XExecutableParameterElement>
        assistedInjectAssistedParametersMap() {
      ImmutableMap.Builder<AssistedParameter, XExecutableParameterElement> builder =
          ImmutableMap.builder();
      for (AssistedParameter assistedParameter : assistedInjectAssistedParameters()) {
        builder.put(assistedParameter, assistedParameter.element());
      }
      return builder.build();
    }

    @Memoized
    public ImmutableMap<AssistedParameter, XExecutableParameterElement>
        assistedFactoryAssistedParametersMap() {
      ImmutableMap.Builder<AssistedParameter, XExecutableParameterElement> builder =
          ImmutableMap.builder();
      for (AssistedParameter assistedParameter : assistedFactoryAssistedParameters()) {
        builder.put(assistedParameter, assistedParameter.element());
      }
      return builder.build();
    }
  }

  /**
   * Metadata about an {@link Assisted} annotated parameter.
   *
   * <p>This parameter can represent an {@link Assisted} annotated parameter from an {@link
   * AssistedInject} constructor or an {@link AssistedFactory} method.
   */
  @AutoValue
  public abstract static class AssistedParameter {
    public static AssistedParameter create(
        XExecutableParameterElement parameter, XType parameterType) {
      AssistedParameter assistedParameter =
          new AutoValue_AssistedInjectionAnnotations_AssistedParameter(
              Optional.ofNullable(parameter.getAnnotation(XTypeNames.ASSISTED))
                  .map(assisted -> assisted.getAsString("value"))
                  .orElse(""),
              parameterType.getTypeName());
      assistedParameter.parameterElement = parameter;
      assistedParameter.parameterType = parameterType;
      return assistedParameter;
    }

    private XExecutableParameterElement parameterElement;
    private XType parameterType;

    /** Returns the string qualifier from the {@link Assisted#value()}. */
    public abstract String qualifier();

    /** Returns the type annotated with {@link Assisted}. */
    abstract TypeName typeName();

    /** Returns the type annotated with {@link Assisted}. */
    public final XType type() {
      return parameterType;
    }

    public final XExecutableParameterElement element() {
      return parameterElement;
    }

    @Override
    public final String toString() {
      return qualifier().isEmpty()
          ? String.format("@Assisted %s", XTypes.toStableString(type()))
          : String.format("@Assisted(\"%s\") %s", qualifier(), XTypes.toStableString(type()));
    }
  }

  public static ImmutableList<AssistedParameter> assistedInjectAssistedParameters(
      XType assistedInjectType) {
    // We keep track of the constructor both as an ExecutableElement to access @Assisted
    // parameters and as an ExecutableType to access the resolved parameter types.
    XConstructorElement assistedInjectConstructor =
        getOnlyElement(assistedInjectedConstructors(assistedInjectType.getTypeElement()));
    XConstructorType assistedInjectConstructorType =
        assistedInjectConstructor.asMemberOf(assistedInjectType.makeNonNullable());

    ImmutableList.Builder<AssistedParameter> builder = ImmutableList.builder();
    for (int i = 0; i < assistedInjectConstructor.getParameters().size(); i++) {
      XExecutableParameterElement parameter = assistedInjectConstructor.getParameters().get(i);
      XType parameterType = assistedInjectConstructorType.getParameterTypes().get(i);
      if (parameter.hasAnnotation(XTypeNames.ASSISTED)) {
        builder.add(AssistedParameter.create(parameter, parameterType));
      }
    }
    return builder.build();
  }

  private static ImmutableList<AssistedParameter> assistedFactoryAssistedParameters(
      XMethodElement factoryMethod, XMethodType factoryMethodType) {
    ImmutableList.Builder<AssistedParameter> builder = ImmutableList.builder();
    for (int i = 0; i < factoryMethod.getParameters().size(); i++) {
      XExecutableParameterElement parameter = factoryMethod.getParameters().get(i);
      XType parameterType = factoryMethodType.getParameterTypes().get(i);
      builder.add(AssistedParameter.create(parameter, parameterType));
    }
    return builder.build();
  }

    /** Logs comprehensive diagnostic information when duplicate methods are detected. */
    private static void logDuplicateMethodDiagnostics(
        XTypeElement factory, ImmutableSet<XMethodElement> methods) {

      // Always print the canonical header so tests (and users) can detect the condition.
      final StringBuilder out = new StringBuilder(2048);
      out.append("\n=== KSP INCREMENTAL PROCESSING BUG DETECTED ===\n")
         .append("Location: AssistedInjectionAnnotations.assistedFactoryMethod()\n")
         .append("Issue: getAllNonPrivateInstanceMethods returned duplicate method elements\n")
         .append("Factory: ").append(factory.getQualifiedName()).append("\n")
         .append("Expected: 1 method, Found: ").append(methods.size()).append("\n");

      // KSP owner diagnostics (best-effort, reflective).
      try {
        Object ksElement = toKS(factory);
        out.append("KS Element Origin: ").append(Reflect.safeCall(ksElement, "getOrigin")).append("\n")
           .append("KS Element Class: ").append(ksElement.getClass().getName()).append("\n")
           .append("KS Element Location: ").append(Reflect.locationString(ksElement)).append("\n")
           .append("KS Element QN: ").append(Reflect.qualifiedName(ksElement)).append("\n");
      } catch (Throwable t) {
        out.append("KS Element Info: Error retrieving (").append(t.getMessage()).append(")\n");
      }

      // Build fingerprints and groupings.
      ImmutableList<MethodFingerprint> fps =
          methods.stream().map(m -> MethodFingerprint.from(factory, m)).collect(toImmutableList());

      out.append("\n=== Summary (by different identity dimensions) ===\n");
      out.append("Distinct JVM descriptors: ")
         .append(fps.stream().map(fp -> nz(fp.jvmDescriptor)).distinct().count()).append("\n");
      out.append("Distinct KS identities:   ")
         .append(fps.stream().map(fp -> nz(fp.ksIdentity)).distinct().count()).append("\n");
      out.append("Distinct KS locations:    ")
         .append(fps.stream().map(fp -> nz(fp.ksLocation)).distinct().count()).append("\n");
      out.append("Distinct owners(FQCN):    ")
         .append(fps.stream().map(fp -> nz(fp.ownerFqcn)).distinct().count()).append("\n");

      // Group by JVM descriptor (null-safe).
      out.append("\n--- Grouping: JVM descriptor ---\n");
      fps.stream()
          .collect(java.util.stream.Collectors.groupingBy(
              fp -> nz(fp.jvmDescriptor),
              java.util.LinkedHashMap::new,
              java.util.stream.Collectors.toList()))
          .forEach((desc, list) -> {
            out.append("  Descriptor: ").append(desc).append("  (").append(list.size()).append(")\n");
            for (MethodFingerprint fp : list) {
              out.append("    • ")
                 .append(nz(fp.ownerFqcn)).append("#").append(nz(fp.jvmName))
                 .append("  params=").append(fp.paramStableTypes)
                 .append("  return=").append(nz(fp.returnStableType)).append("\n")
                 .append("      KS: id=").append(nz(fp.ksIdentity))
                 .append(" origin=").append(nz(fp.ksOrigin))
                 .append(" file=").append(nz(fp.ksFilePath))
                 .append(" loc=").append(nz(fp.ksLocation)).append("\n");
            }
          });

      // Group by KS identity (null-safe).
      out.append("\n--- Grouping: KS identity ---\n");
      fps.stream()
          .collect(java.util.stream.Collectors.groupingBy(
              fp -> nz(fp.ksIdentity),
              java.util.LinkedHashMap::new,
              java.util.stream.Collectors.toList()))
          .forEach((id, list) -> {
            out.append("  KS id ").append(id).append(" (").append(list.size()).append(")\n");
            for (MethodFingerprint fp : list) {
              out.append("    • ").append(nz(fp.ownerFqcn)).append("#").append(nz(fp.jvmName))
                 .append("  desc=").append(nz(fp.jvmDescriptor))
                 .append("  file=").append(nz(fp.ksFilePath))
                 .append("  loc=").append(nz(fp.ksLocation)).append("\n");
            }
          });

      out.append("\n=== Per-method detail ===\n");
      // Only compute 'first' when there is at least one method (prevents NPE in empty case).
      final XMethodElement first = methods.isEmpty() ? null : methods.iterator().next();
      int idx = 0;
      for (XMethodElement m : methods) {
        idx++;
        MethodFingerprint fp = fps.get(idx - 1);

        out.append("--- Method #").append(idx).append(" ---\n");
        out.append("Name: ").append(nz(m.getJvmName())).append("\n");
        out.append("Descriptor: ").append(nz(fp.jvmDescriptor)).append("\n");
        out.append("Class: ").append(m.getClass().getName()).append("\n");
        out.append("Java Identity Hash: ").append(System.identityHashCode(m)).append("\n");
        out.append("Owner: ").append(nz(fp.ownerFqcn)).append("\n");

        out.append("Flags: ")
           .append("abstract=").append(m.isAbstract())
           .append(" javaDefault=").append(m.isJavaDefault())
           .append(" kotlinDefaultImpl=").append(safeBool(m::hasKotlinDefaultImpl))
           .append(" suspend=").append(safeBool(m::isSuspendFunction))
           .append(" extension=").append(safeBool(m::isExtensionFunction))
           .append(" propertyMethod=").append(safeBool(m::isKotlinPropertyMethod))
           .append(" getter=").append(safeBool(m::isKotlinPropertyGetter))
           .append(" setter=").append(safeBool(m::isKotlinPropertySetter))
           .append("\n");

        out.append("Parameters (resolved): ").append(fp.paramStableTypes).append("\n");
        out.append("Return (resolved): ").append(nz(fp.returnStableType)).append("\n");
        try {
          ImmutableList<String> throwsTypes =
              m.getThrownTypes().stream().map(XTypes::toStableString).collect(toImmutableList());
          out.append("Throws: ").append(throwsTypes).append("\n");
        } catch (Throwable t) {
          out.append("Throws: <error ").append(t.getMessage()).append(">\n");
        }

        try {
          Object enclosing = m.getEnclosingElement();
          out.append("Enclosing: ").append(enclosing).append("  (")
             .append(enclosing.getClass().getName()).append(") ")
             .append(" id=").append(System.identityHashCode(enclosing)).append("\n");
        } catch (Throwable t) {
          out.append("Enclosing: <error ").append(t.getMessage()).append(">\n");
        }

        try {
          Object ksMethod = toKS(m);
          out.append("KS: class=").append(ksMethod.getClass().getName())
             .append(" id=").append(System.identityHashCode(ksMethod)).append("\n");
          out.append("    origin=").append(Reflect.safeCall(ksMethod, "getOrigin")).append("\n");
          out.append("    simpleName=").append(Reflect.simpleName(ksMethod)).append("\n");
          out.append("    qualifiedName=").append(Reflect.qualifiedName(ksMethod)).append("\n");
          out.append("    file=").append(Reflect.containingFilePath(ksMethod)).append("\n");
          out.append("    location=").append(Reflect.locationString(ksMethod)).append("\n");
          out.append("    wrappers=").append(Reflect.wrapperFieldSummary(ksMethod)).append("\n");
        } catch (Throwable t) {
          out.append("KS: <error ").append(t.getMessage()).append(">\n");
        }

        if (idx > 1 && first != null) {
            out.append("equals() with Method #1: ").append(m.equals(first))
                    .append(", == ").append(m == first)
                    .append(", hashEq=").append(m.hashCode() == first.hashCode())
                    .append("\n");
        }
      }

      out.append("\n=== Environment (breadcrumbs) ===\n");
      out.append("java.version=").append(nz(System.getProperty("java.version"))).append("\n");
      out.append("java.vendor=").append(nz(System.getProperty("java.vendor"))).append("\n");
      out.append("os.name=").append(nz(System.getProperty("os.name"))).append("\n");
      out.append("user.dir=").append(nz(System.getProperty("user.dir"))).append("\n");
      out.append("org.gradle.appname=").append(nz(System.getProperty("org.gradle.appname"))).append("\n");
      out.append("kotlin.compiler.execution.strategy=")
         .append(nz(System.getProperty("kotlin.compiler.execution.strategy"))).append("\n");
      out.append("ksp.incremental=").append(nz(System.getProperty("ksp.incremental"))).append("\n");
      out.append("\n=== END KSP BUG DIAGNOSTIC ===\n");

      System.err.println(out.toString());
    }

    // Null-to-string helper for grouping keys & prints.
    private static String nz(Object o) { return String.valueOf(o); }

    private static boolean safeBool(java.util.concurrent.Callable<Boolean> c) {
      try { return Boolean.TRUE.equals(c.call()); } catch (Throwable t) { return false; }
    }

    /** Captures stable identity across JVM and KS layers for a method. */
    private static final class MethodFingerprint {
      final String ownerFqcn;
      final String jvmName;
      final String jvmDescriptor;
      final ImmutableList<String> paramStableTypes;
      final String returnStableType;

      // KS reflective bits (best-effort)
      final String ksIdentity;
      final String ksOrigin;
      final String ksFilePath;
      final String ksLocation;

      private MethodFingerprint(
          String ownerFqcn,
          String jvmName,
          String jvmDescriptor,
          ImmutableList<String> paramStableTypes,
          String returnStableType,
          String ksIdentity,
          String ksOrigin,
          String ksFilePath,
          String ksLocation) {
        this.ownerFqcn = ownerFqcn;
        this.jvmName = jvmName;
        this.jvmDescriptor = jvmDescriptor;
        this.paramStableTypes = paramStableTypes;
        this.returnStableType = returnStableType;
        this.ksIdentity = ksIdentity;
        this.ksOrigin = ksOrigin;
        this.ksFilePath = ksFilePath;
        this.ksLocation = ksLocation;
      }

      static MethodFingerprint from(XTypeElement factory, XMethodElement m) {
        String owner = factory.getQualifiedName();
        String desc;
        try {
          desc = m.getJvmDescriptor();
          if (desc == null) desc = "<null-descriptor>"; // ← null-proof
        } catch (Throwable t) {
          desc = "<error:" + t.getMessage() + ">";
        }

        ImmutableList<String> params;
        String ret;
        try {
          XMethodType mt = m.asMemberOf(factory.getType());
          params = mt.getParameterTypes().stream()
              .map(XTypes::toStableString)
              .collect(toImmutableList());
          ret = XTypes.toStableString(mt.getReturnType());
        } catch (Throwable t) {
          params = ImmutableList.of("<error:" + t.getMessage() + ">");
          ret = "<error:" + t.getMessage() + ">";
        }

        String ksId = "n/a", ksOrigin = "n/a", ksFile = "n/a", ksLoc = "n/a";
        try {
          Object ks = toKS(m);
          ksId = String.valueOf(System.identityHashCode(ks));
          ksOrigin = String.valueOf(Reflect.safeCall(ks, "getOrigin"));
          ksFile = Reflect.containingFilePath(ks);
          ksLoc = Reflect.locationString(ks);
        } catch (Throwable ignore) {}

        return new MethodFingerprint(
            owner,
            m.getJvmName(),
            desc,
            params,
            ret,
            ksId,
            ksOrigin,
            ksFile,
            ksLoc);
      }
    }

    /** Reflection helpers against KSP symbols, without adding a compile-time dependency. */
    private static final class Reflect {
      static Object safeCall(Object target, String method) {
        if (target == null) return "null";
        try {
          java.lang.reflect.Method m = target.getClass().getMethod(method);
          Object v = m.invoke(target);
          return (v == null) ? "null" : v;
        } catch (Throwable t) {
          return "<error:" + t.getMessage() + ">";
        }
      }

      static String simpleName(Object ksDecl) {
        try {
          Object name = ksDecl.getClass().getMethod("getSimpleName").invoke(ksDecl); // KSName
          return String.valueOf(name.getClass().getMethod("asString").invoke(name));
        } catch (Throwable t) { return "<error:" + t.getMessage() + ">"; }
      }

      static String qualifiedName(Object ksDecl) {
        try {
          java.lang.reflect.Method m = ksDecl.getClass().getMethod("getQualifiedName"); // KSName?
          Object name = m.invoke(ksDecl);
          if (name == null) return "null";
          return String.valueOf(name.getClass().getMethod("asString").invoke(name));
        } catch (NoSuchMethodException nsme) {
          try {
            Object pkg = ksDecl.getClass().getMethod("getPackageName").invoke(ksDecl);
            String pkgStr = String.valueOf(pkg.getClass().getMethod("asString").invoke(pkg));
            return pkgStr + "." + simpleName(ksDecl);
          } catch (Throwable t) {
            return "<error:" + nsme.getMessage() + ">";
          }
        } catch (Throwable t) {
          return "<error:" + t.getMessage() + ">";
        }
      }

      static String containingFilePath(Object ksNode) {
        try {
          Object file = ksNode.getClass().getMethod("getContainingFile").invoke(ksNode);
          if (file == null) return "null";
          return String.valueOf(file.getClass().getMethod("getFilePath").invoke(file));
        } catch (Throwable t) {
          return "n/a";
        }
      }

      static String locationString(Object ksNode) {
        try {
          Object loc = ksNode.getClass().getMethod("getLocation").invoke(ksNode);
          if (loc == null) return "null";
          String cls = loc.getClass().getName();
          String file = tryCall(loc, "getFilePath");
          String start = tryCall(loc, "getStartOffset");
          String end = tryCall(loc, "getEndOffset");
          if (!"n/a".equals(file)) return file + ":" + start + ".." + end;
          return cls + " " + String.valueOf(loc);
        } catch (Throwable t) {
          return "<error:" + t.getMessage() + ">";
        }
      }

      static String wrapperFieldSummary(Object ksObj) {
        try {
          StringBuilder sb = new StringBuilder();
          for (java.lang.reflect.Field f : ksObj.getClass().getDeclaredFields()) {
            String n = f.getName();
            if (n.contains("wrapped") || n.contains("element") || n.contains("declaration") || n.contains("symbol")) {
              f.setAccessible(true);
              Object v = f.get(ksObj);
              if (v != null) {
                sb.append(n).append("=")
                  .append(v.getClass().getSimpleName())
                  .append("#").append(System.identityHashCode(v))
                  .append(" ");
              }
            }
          }
          return sb.length() == 0 ? "<none>" : sb.toString().trim();
        } catch (Throwable t) {
          return "<error:" + t.getMessage() + ">";
        }
      }

      private static String tryCall(Object target, String method) {
        try { return String.valueOf(target.getClass().getMethod(method).invoke(target)); }
        catch (Throwable t) { return "n/a"; }
      }
    }

  private AssistedInjectionAnnotations() {}
}
