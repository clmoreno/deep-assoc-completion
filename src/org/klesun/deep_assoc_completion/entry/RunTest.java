package org.klesun.deep_assoc_completion.entry;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiFile;
import com.intellij.ui.awt.RelativePoint;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import org.apache.commons.collections.CollectionUtils;
import org.klesun.deep_assoc_completion.DeepType;
import org.klesun.deep_assoc_completion.completion_providers.DeepKeysPvdr;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.deep_assoc_completion.helpers.SearchContext;
import org.klesun.deep_assoc_completion.resolvers.ClosRes;
import org.klesun.lang.It;
import org.klesun.lang.L;
import org.klesun.lang.Opt;
import org.klesun.lang.Tls;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import static org.klesun.lang.Lang.*;

public class RunTest extends AnAction
{
    private static Opt<It<Method>> findTestDataPvdrFuncs(PsiFile psiFile)
    {
        It<Method> meths = It(PhpIndex.getInstance(psiFile.getProject()).getClassesByName("UnitTest"))
            .fap(cls -> cls.getMethods())
            .flt(m -> m.getName().startsWith("provide"));

        return meths.has() ? opt(meths) : opt(null);
    }

    private static Opt<It<Method>> findExactKeysTestDataPvdrFuncs(PsiFile psiFile)
    {
        It<Method> meths = It(PhpIndex.getInstance(psiFile.getProject()).getClassesByName("ExactKeysUnitTest"))
            .fap(cls -> cls.getMethods())
            .flt(m -> m.getName().startsWith("provide"));

        return meths.has() ? opt(meths) : opt(null);
    }

    private static It<T3<CaseContext, DeepType.Key, DeepType.Key>> parseReturnedTestCase(Method func, Logger logger)
    {
        return ClosRes.findFunctionReturns(func)
            .map(ret -> ret.getArgument())
            .fop(toCast(PhpExpression.class))
            .fap(retVal -> {
                SearchContext search = new SearchContext(retVal.getProject())
                    .setDepth(DeepKeysPvdr.getMaxDepth(false, retVal.getProject()));
                FuncCtx funcCtx = new FuncCtx(search);
                return funcCtx.findExprType(retVal);
            })
            .fap(t -> Mt.getKeySt(t, null))
            .fap((rett, i) -> {
                CaseContext ctx = new CaseContext(logger);
                ctx.dataProviderName = func.getName();
                ctx.testNumber = i;
                return rett.keys.flt(k -> k.keyType.getNames().any(n -> n.equals("0")))
                    .fap(actual -> rett.keys.flt(k -> k.keyType.getNames().any(n -> n.equals("1")))
                        .map(expected -> T3(ctx, actual, expected)));
            });
    }

    @Override
    public void actionPerformed(AnActionEvent e)
    {
        long startTime = System.nanoTime();
        Logger logger = new Logger();
        logger.logMsg("Searching for \"UnitTest\" class in project...");
        It<Error> exactKeyErrors = opt(e.getData(LangDataKeys.PSI_FILE))
            .fop(file -> findExactKeysTestDataPvdrFuncs(file))
            .els(() -> System.out.println("Failed to find data-providing functions"))
            .fap(funcs -> funcs.fap(f -> parseReturnedTestCase(f, logger)))
            .fap(tuple -> {
                L<String> actualKeys = tuple.b.getTypes().fap(t -> t.keys).fap(k -> k.keyType.getNames()).arr();
                L<String> expectedKeys = new Mt(tuple.c.getTypes())
                    .getKey(null).getStringValues().arr();
                try {
                    //logger.logMsg("doing " + tuple.a.dataProviderName + " #" + tuple.a.testNumber);
                    return tuple.a.testCaseExact(actualKeys, expectedKeys);
                } catch (RuntimeException exc) {
                    String msg = "Exception was thrown: " + exc.getClass() + " " + exc.getMessage()
                        // + "\n" + Tls.getStackTrace(exc)
                        ;
                    return list(new Error(tuple.a, msg));
                }
            });
        It<Error> errors = opt(e.getData(LangDataKeys.PSI_FILE))
            .fop(file -> findTestDataPvdrFuncs(file))
            .els(() -> System.out.println("Failed to find data-providing functions"))
            .fap(funcs -> funcs.fap(f -> parseReturnedTestCase(f, logger)))
            .fap(tuple -> {
                try {
                    //logger.logMsg("doing " + tuple.a.dataProviderName + " #" + tuple.a.testNumber);
                    return tuple.a.testCasePartial(list(tuple.b), tuple.c);
                } catch (RuntimeException exc) {
                    String msg = "Exception was thrown: " + exc.getClass() + " " + exc.getMessage()
                        // + "\n" + Tls.getStackTrace(exc)
                        ;
                    return list(new Error(tuple.a, msg));
                }
            });

        L<Error> msgs = It.cnc(errors, exactKeyErrors).arr();
        logger.logMsg("");
        msgs.fch(logger::logErr);
        double seconds = (System.nanoTime() - startTime) / 1000000000.0;
        logger.logMsg("Done testing with " + logger.errCnt + " errors and " + logger.sucCnt + " OK-s in " + seconds + " s. \n");
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder("<pre>" + logger.wholeText + "</pre>", MessageType.INFO, null)
            .setFadeoutTime(300 * 1000)
            .createBalloon()
            .show(RelativePoint.fromScreen(new Point(200, 200)), Balloon.Position.atRight);
    }

    private static class CaseContext
    {
        Logger logger;

        String dataProviderName;
        List<String> keyChain = list();
        int testNumber;

        public CaseContext(Logger logger)
        {
            this.logger = logger;
        }

        private L<Error> testCasePartial(List<DeepType.Key> actual, DeepType.Key expected) {
            L<Error> errors = list();

            DeepType expectedt = expected.getTypes().fst().unw();
            expectedt.keys.fch((subExpected) -> subExpected.keyType.getNames().fch(subKey -> {
                It<DeepType.Key> havingKey = It(actual)
                    .fap(krecs -> krecs.getTypes())
                    .fap(t -> t.keys)
                    .flt(k -> k.keyType.getNames().any(n -> n.equals(subKey)));

                if (!havingKey.has()) {
                    logger.logErrShort();
                    errors.add(new Error(this, "No such key: " + subKey));
                } else {
                    logger.logSucShort();
                    keyChain.add(subKey);
                    testCasePartial(havingKey.arr(), subExpected);
                    keyChain.remove(keyChain.size() - 1);
                }
            }));

            return errors;
        }

        private List<Error> testCaseExact(L<String> actual, L<String> expected) {
            List<Error> errors = list();
            Collection<String> unexpectedKeys = CollectionUtils.subtract(new LinkedHashSet(actual), new LinkedHashSet(expected));
            Collection<String> absentKeys = CollectionUtils.subtract(new LinkedHashSet(expected), new LinkedHashSet(actual));
            if (!absentKeys.isEmpty()) {
                errors.add(new Error(this, "Result does not have expected keys: " + Tls.implode(", ", L(absentKeys))));
            }
            if (!unexpectedKeys.isEmpty()) {
                errors.add(new Error(this, "Result has unexpected keys: " + Tls.implode(", ", L(unexpectedKeys))));
            }
            if (unexpectedKeys.isEmpty() && absentKeys.isEmpty()) {
                logger.logSucShort();
            } else {
                logger.logErrShort();
            }
            return errors;
        }
    }

    private static class Error
    {
        String message;
        String dataProviderName;
        List<String> keyChain;
        int testNumber;

        Error(CaseContext ctx, String msg)
        {
            this.dataProviderName = ctx.dataProviderName;
            this.keyChain = new ArrayList(ctx.keyChain);
            this.testNumber = ctx.testNumber;
            this.message = msg;
        }
    }

    private static class Logger
    {
        String wholeText = "";
        int caret = 0;
        int sucCnt = 0;
        int errCnt = 0;

        void logMsg(String msg)
        {
            System.out.println(msg);
            wholeText += msg + "\n";
            caret = 0;
        }

        void printWrapped(String text)
        {
            System.out.print(text);
            wholeText += text;
            L<String> lines = L(text.split("/\n/"));
            if (lines.size() > 1) {
                caret = 0;
            }
            caret += lines.lst().unw().length();
            if (caret > 90) {
                logMsg("");
            }
        }

        void logErr(Error err)
        {
            String msg = "Error in " + err.dataProviderName + " #" + err.testNumber + " " +
                L(err.keyChain).rdc((a,b) -> a + ", " + b, "") + " " + err.message;
            logMsg(msg);
        }

        void logErrShort()
        {
            printWrapped("E");
            ++errCnt;
        }

        void logSucShort()
        {
            printWrapped(".");
            ++sucCnt;
        }
    }
}
