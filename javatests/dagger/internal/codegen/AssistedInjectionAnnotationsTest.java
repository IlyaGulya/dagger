/*
 * Copyright (C) 2024 The Dagger Authors.
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

package dagger.internal.codegen;

import static com.google.common.truth.Truth.assertThat;
import static dagger.internal.codegen.binding.AssistedInjectionAnnotations.assistedFactoryMethod;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.collect.ImmutableList;
import dagger.internal.codegen.binding.AssistedInjectionAnnotations;
import dagger.internal.codegen.xprocessing.XTypeElements;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class AssistedInjectionAnnotationsTest {

  private MockedStatic<XTypeElements> mockedXTypeElements;
  private ByteArrayOutputStream errorOutput;
  private PrintStream originalErr;

  @Before
  public void setUp() {
    mockedXTypeElements = Mockito.mockStatic(XTypeElements.class);
    
    // Capture System.err output to verify logging
    errorOutput = new ByteArrayOutputStream();
    originalErr = System.err;
    System.setErr(new PrintStream(errorOutput));
  }

  @After
  public void tearDown() {
    mockedXTypeElements.close();
    System.setErr(originalErr);
  }

  @Test
  public void assistedFactoryMethod_duplicateMethodsFromKsp_recoversGracefully() {
    // Create mock factory and method elements
    XTypeElement mockFactory = mock(XTypeElement.class);
    when(mockFactory.getQualifiedName()).thenReturn("com.example.TestFactory");

    XMethodElement mockMethod1 = mock(XMethodElement.class);
    XMethodElement mockMethod2 = mock(XMethodElement.class);
    
    when(mockMethod1.getJvmName()).thenReturn("create");
    when(mockMethod2.getJvmName()).thenReturn("create");
    
    // Mock getEnclosingElement() and getClassName()
    XTypeElement mockEnclosingElement1 = mock(XTypeElement.class);
    XTypeElement mockEnclosingElement2 = mock(XTypeElement.class);
    
    when(mockMethod1.getEnclosingElement()).thenReturn(mockEnclosingElement1);
    when(mockMethod2.getEnclosingElement()).thenReturn(mockEnclosingElement2);
    
    com.squareup.javapoet.ClassName mockClassName1 = com.squareup.javapoet.ClassName.get("com.example", "TestFactory");
    com.squareup.javapoet.ClassName mockClassName2 = com.squareup.javapoet.ClassName.get("com.example", "TestFactory");
    
    when(mockEnclosingElement1.getClassName()).thenReturn(mockClassName1);
    when(mockEnclosingElement2.getClassName()).thenReturn(mockClassName2);

    // Simulate KSP returning duplicate method elements in the raw list
    // but Set deduplication during assistedFactoryMethods() call
    ImmutableList<XMethodElement> duplicateMethodsList = ImmutableList.of(mockMethod1, mockMethod2);
    
    // Mock XTypeElements.getAllNonPrivateInstanceMethods to return duplicates
    mockedXTypeElements.when(() -> XTypeElements.getAllNonPrivateInstanceMethods(mockFactory))
        .thenReturn(duplicateMethodsList);
    
    // Mock the methods to be abstract and non-default
    when(mockMethod1.isAbstract()).thenReturn(true);
    when(mockMethod2.isAbstract()).thenReturn(true);
    when(mockMethod1.isJavaDefault()).thenReturn(false);
    when(mockMethod2.isJavaDefault()).thenReturn(false);

    // Call assistedFactoryMethod - this should handle duplicates gracefully
    XMethodElement result = assistedFactoryMethod(mockFactory);
    
    // Verify we got a method back (either one, since they're duplicates)
    assertThat(result).isNotNull();
    assertThat(result.getJvmName()).isEqualTo("create");
    
    // Verify comprehensive logging was output
    String logOutput = errorOutput.toString();
    System.out.println(logOutput);
    assertThat(logOutput).contains("Factory: com.example.TestFactory");
    assertThat(logOutput).contains("Expected: 1 method, Found: 2");
    assertThat(logOutput).contains("Method #1");
    assertThat(logOutput).contains("Method #2");
    assertThat(logOutput).contains("Name: create");
    assertThat(logOutput).contains("Java Identity Hash:");
    assertThat(logOutput).contains("equals() with Method #1:");
  }

  @Test
  public void assistedFactoryMethod_noMethods_throwsIllegalArgumentException() {
    XTypeElement mockFactory = mock(XTypeElement.class);
    when(mockFactory.getQualifiedName()).thenReturn("com.example.EmptyFactory");

    // Mock XTypeElements.getAllNonPrivateInstanceMethods to return empty list
    mockedXTypeElements.when(() -> XTypeElements.getAllNonPrivateInstanceMethods(mockFactory))
        .thenReturn(ImmutableList.of());

    // Should throw IllegalArgumentException for empty methods
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class, 
        () -> assistedFactoryMethod(mockFactory));
        
    assertThat(exception.getMessage()).contains("Factory com.example.EmptyFactory has no assisted factory methods");
    assertThat(exception.getMessage()).contains("This should have been caught during validation");
    
    // Verify error logging
    String logOutput = errorOutput.toString();
    assertThat(logOutput).contains("Expected: 1 method, Found: 0");
  }

  @Test  
  public void assistedFactoryMethod_singleMethod_worksNormally() {
    XTypeElement mockFactory = mock(XTypeElement.class);
    when(mockFactory.getQualifiedName()).thenReturn("com.example.NormalFactory");

    XMethodElement mockMethod = mock(XMethodElement.class);
    when(mockMethod.getJvmName()).thenReturn("create");
    when(mockMethod.isAbstract()).thenReturn(true);
    when(mockMethod.isJavaDefault()).thenReturn(false);

    // Mock XTypeElements.getAllNonPrivateInstanceMethods to return single method
    mockedXTypeElements.when(() -> XTypeElements.getAllNonPrivateInstanceMethods(mockFactory))
        .thenReturn(ImmutableList.of(mockMethod));

    // Should work normally without any logging
    XMethodElement result = assistedFactoryMethod(mockFactory);
    
    assertThat(result).isSameInstanceAs(mockMethod);
    
    // Verify no error logging occurred
    String logOutput = errorOutput.toString();
    assertThat(logOutput).isEmpty();
  }

}
