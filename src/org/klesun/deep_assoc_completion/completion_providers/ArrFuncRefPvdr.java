package org.klesun.deep_assoc_completion.completion_providers;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.impl.ArrayCreationExpressionImpl;
import org.jetbrains.annotations.NotNull;
import org.klesun.deep_assoc_completion.helpers.ExprCtx;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.SearchContext;
import org.klesun.deep_assoc_completion.resolvers.ArrCtorRes;
import org.klesun.lang.It;
import org.klesun.lang.L;

import static org.klesun.lang.Lang.*;

/**
 * provides completion of method names in `[Some\ClassName::class, '']`
 */
public class ArrFuncRefPvdr extends CompletionProvider<CompletionParameters>
{
    private static LookupElement makeLookup(Method method)
    {
        return LookupElementBuilder.create(method.getName())
            .bold()
            .withIcon(DeepKeysPvdr.getIcon())
            .withTypeText(method.getLocalType(false).filterUnknown().toString());
    }

    @Override
    protected void addCompletions(@NotNull CompletionParameters parameters, ProcessingContext processingContext, @NotNull CompletionResultSet result)
    {
        SearchContext search = new SearchContext(parameters)
            .setDepth(DeepKeysPvdr.getMaxDepth(parameters));
        FuncCtx funcCtx = new FuncCtx(search);
        long startTime = System.nanoTime();
        It<Method> methods = opt(parameters.getPosition().getParent())
            .fap(literal -> opt(literal.getParent())
                .map(arrVal -> arrVal.getParent())
                .fop(toCast(ArrayCreationExpressionImpl.class))
                .map(arrCtor -> L(arrCtor.getChildren()))
                .flt(params -> params.size() == 2)
                .flt(params -> literal.isEquivalentTo(params.get(1).getFirstChild()))
                .fop(params -> params.gat(0))
                .fap(clsPsi -> list(
                    ArrCtorRes.resolveClass(clsPsi)
                        .fap(cls -> It(cls.getMethods()))
                        .flt(meth -> meth.isStatic()),
                    new ArrCtorRes(new ExprCtx(funcCtx, clsPsi, 0))
                        .resolveInstance(clsPsi)
                        .fap(cls -> It(cls.getMethods()))
                        .flt(meth -> meth.getMethodType(false) != Method.MethodType.CONSTRUCTOR)
                        .flt(meth -> !meth.isStatic()))
                ))
            .fap(a -> a);

        methods.map(m -> makeLookup(m)).fch(result::addElement);
        long elapsed = System.nanoTime() - startTime;
        String msg = "Resolved " + search.getExpressionsResolved() + " expressions in " + (elapsed / 1000000000.0) + " seconds";
        result.addLookupAdvertisement(msg);

        System.out.println(msg);
    }
}
