package org.klesun.deep_assoc_completion.go_to_decl_providers;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.MemberReference;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import org.jetbrains.annotations.Nullable;
import org.klesun.deep_assoc_completion.completion_providers.DeepKeysPvdr;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.deep_assoc_completion.helpers.SearchContext;
import org.klesun.deep_assoc_completion.resolvers.ArrCtorRes;
import org.klesun.lang.It;
import org.klesun.lang.L;
import org.klesun.lang.Lang;

import java.util.Objects;

/**
 * for cases when built-in Type Provider failed to determine
 * class of object - try ourselves using the Deep Resolver
 */
public class DeepObjMemberGoToDecl extends Lang implements GotoDeclarationHandler
{
    private It<? extends PsiElement> resolveMember(PhpClass cls, String name)
    {
        return list(
            It(cls.getFields()).flt(f -> f.getName().equals(name)),
            It(cls.getMethods()).flt(m -> m.getName().equals(name))
        ).fap(a -> a);
    }

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(@Nullable PsiElement psiElement, int i, Editor editor)
    {
        SearchContext search = new SearchContext(editor.getProject())
            .setDepth(DeepKeysPvdr.getMaxDepth(false, opt(psiElement).map(psi -> psi.getProject()).def(null)));
        FuncCtx funcCtx = new FuncCtx(search);

        L<? extends PsiElement> psiTargets = opt(psiElement)
            .map(leaf -> leaf.getParent())
            .fop(toCast(MemberReference.class))
            .flt(mem -> mem.multiResolve(false).length == 0)
            .fap(mem -> opt(mem.getFirstChild())
                .fop(toCast(PhpExpression.class))
                .map(exp -> funcCtx.findExprType(exp).wap(Mt::new))
                .fap(mt -> list(
                    ArrCtorRes.resolveMtCls(mt, mem.getProject())
                        .fap(cls -> resolveMember(cls, mem.getName())),
                    mt.getProps()
                        .fap(prop -> prop.keyType.getTypes.get())
                        .flt(propt -> Objects.equals(propt.stringValue, mem.getName()))
                        .map(prop -> prop.definition)
                ).fap(a -> a))
            ).arr();

        return psiTargets.toArray(new PsiElement[psiTargets.size()]);
    }

    @Nullable
    @Override
    public String getActionText(DataContext dataContext)
    {
        return null;
    }
}
