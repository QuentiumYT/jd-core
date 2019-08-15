/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1;

import junit.framework.TestCase;
import org.jd.core.v1.compiler.CompilerUtil;
import org.jd.core.v1.compiler.JavaSourceFileObject;
import org.jd.core.v1.loader.ZipLoader;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.printer.PlainTextPrinter;
import org.jd.core.v1.service.converter.classfiletojavasyntax.ClassFileToJavaSyntaxProcessor;
import org.jd.core.v1.service.deserializer.classfile.DeserializeClassFileProcessor;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.JavaSyntaxToJavaFragmentProcessor;
import org.jd.core.v1.service.layouter.LayoutFragmentProcessor;
import org.jd.core.v1.service.tokenizer.javafragmenttotoken.JavaFragmentToTokenProcessor;
import org.jd.core.v1.service.writer.WriteTokenProcessor;
import org.jd.core.v1.util.DefaultList;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class JarFileToJavaSourceTest extends TestCase {
    protected DeserializeClassFileProcessor deserializer = new DeserializeClassFileProcessor();
    protected ClassFileToJavaSyntaxProcessor converter = new ClassFileToJavaSyntaxProcessor();
    protected JavaSyntaxToJavaFragmentProcessor fragmenter = new JavaSyntaxToJavaFragmentProcessor();
    protected LayoutFragmentProcessor layouter = new LayoutFragmentProcessor();
    //protected TestTokenizeJavaFragmentProcessor tokenizer = new TestTokenizeJavaFragmentProcessor();
    protected JavaFragmentToTokenProcessor tokenizer = new JavaFragmentToTokenProcessor();
    protected WriteTokenProcessor writer = new WriteTokenProcessor();

    @Test
    public void testCommonsCollections4() throws Exception {
        // Test 'commons-collections4-4.1.jar'
        test(org.apache.commons.collections4.CollectionUtils.class);
    }

    protected void test(Class clazz) throws Exception {
        test(new FileInputStream(Paths.get(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile()));
    }

    protected void test(InputStream inputStream) throws Exception {
        long fileCounter = 0;
        long exceptionCounter = 0;
        long assertFailedCounter = 0;
        long recompilationFailedCounter = 0;

        try (InputStream is = inputStream) {
            ZipLoader loader = new ZipLoader(is);
            CounterPrinter printer = new CounterPrinter();
            HashMap<String, Integer> statistics = new HashMap<>();

            Message message = new Message();
            message.setHeader("loader", loader);
            message.setHeader("printer", printer);
            message.setHeader("configuration", Collections.singletonMap("realignLineNumbers", Boolean.TRUE));

            for (String path : loader.getMap().keySet()) {
                if (path.endsWith(".class") && (path.indexOf('$') == -1)) {
                    String internalTypeName = path.substring(0, path.length() - 6); // 6 = ".class".length()
                    message.setHeader("mainInternalTypeName", internalTypeName);
                    printer.init();

                    fileCounter++;

                    try {
                        // Decompile class
                        deserializer.process(message);
                        converter.process(message);
                        fragmenter.process(message);
                        layouter.process(message);
                        tokenizer.process(message);
                        writer.process(message);
                    } catch (AssertionError e) {
                        String msg = (e.getMessage() == null) ? "<?>" : e.getMessage();
                        Integer counter = statistics.get(msg);
                        statistics.put(msg, (counter == null) ? 1 : counter + 1);
                        assertFailedCounter++;
                    } catch (Throwable t) {
                        String msg = t.getMessage() == null ? t.getClass().toString() : t.getMessage();
                        Integer counter = statistics.get(msg);
                        statistics.put(msg, (counter == null) ? 1 : counter + 1);
                        exceptionCounter++;
                    }

                    // Recompile source
                    String source = printer.toString();

                    // TODO if (!CompilerUtil.compile("1.8", new JavaSourceFileObject(internalTypeName, source))) {
                    // TODO     recompilationFailedCounter++;
                    // TODO }
                }
            }

            System.out.println("Counters:");
            System.out.println("  fileCounter             =" + fileCounter);
            System.out.println("  class+innerClassCounter =" + printer.classCounter);
            System.out.println("  methodCounter           =" + printer.methodCounter);
            System.out.println("  exceptionCounter        =" + exceptionCounter);
            System.out.println("  assertFailedCounter     =" + assertFailedCounter);
            System.out.println("  errorInMethodCounter    =" + printer.errorInMethodCounter);
            System.out.println("  accessCounter           =" + printer.accessCounter);
            System.out.println("  recompilationFailed     =" + recompilationFailedCounter);
            System.out.println("Percentages:");
            System.out.println("  % exception             =" + (exceptionCounter * 100F / fileCounter));
            System.out.println("  % assert failed         =" + (assertFailedCounter * 100F / fileCounter));
            System.out.println("  % error in method       =" + (printer.errorInMethodCounter * 100F / printer.methodCounter));
            System.out.println("  % recompilation failed  =" + (recompilationFailedCounter * 100F / fileCounter));

            System.out.println("Errors:");
            DefaultList<String> stats = new DefaultList<>();
            for (Map.Entry<String, Integer> stat : statistics.entrySet()) {
                stats.add("  " + stat.getValue() + " \t: " + stat.getKey());
            }
            stats.sort((s1, s2) -> Integer.parseInt(s2.substring(0, 5).trim()) - Integer.parseInt(s1.substring(0, 5).trim()));
            for (String stat : stats) {
                System.out.println(stat);
            }

            assertTrue(exceptionCounter == 0);
            assertTrue(assertFailedCounter == 0);
            // TODO assertTrue(recompilationFailedCounter == 0);
        }
    }

    protected static class CounterPrinter extends PlainTextPrinter {
        public long classCounter = 0;
        public long methodCounter = 0;
        public long errorInMethodCounter = 0;
        public long accessCounter = 0;

        public void printText(String text) {
            if (text != null) {
                if ("// Byte code:".equals(text) || text.startsWith("/* monitor enter ") || text.startsWith("/* monitor exit ")) {
                    errorInMethodCounter++;
                }
            }
            super.printText(text);
        }

        public void printDeclaration(int type, String internalTypeName, String name, String descriptor) {
            if (type == TYPE) classCounter++;
            if ((type == METHOD) || (type == CONSTRUCTOR)) methodCounter++;
            super.printDeclaration(type, internalTypeName, name, descriptor);
        }

        public void printReference(int type, String internalTypeName, String name, String descriptor, String ownerInternalName) {
            if ((name != null) && name.startsWith("access$")) {
                accessCounter++;
            }
            super.printReference(type, internalTypeName, name, descriptor, ownerInternalName);
        }
    }
}
