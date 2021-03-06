package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.jetbrains.php.lang.psi.elements.MemberReference;
import com.jetbrains.php.lang.psi.elements.NewExpression;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.impl.BinaryExpressionImpl;
import com.jetbrains.php.lang.psi.elements.impl.NewExpressionImpl;
import com.jetbrains.php.lang.psi.elements.impl.TernaryExpressionImpl;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.apache.commons.lang.StringEscapeUtils;
import org.klesun.deep_assoc_completion.DeepType;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.IExprCtx;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.lang.*;

import javax.annotation.Nullable;

public class MiscRes extends Lang
{
    final private IExprCtx ctx;

    public MiscRes(IExprCtx ctx)
    {
        this.ctx = ctx;
    }

    private It<DeepType> findPsiExprType(PsiElement psi)
    {
        return Tls.cast(PhpExpression.class, psi)
            .fap(casted -> ctx.findExprType(casted));
    }

    public It<PhpType> resolveAnyClassReference(@Nullable PhpExpression clsRefPsi, boolean ideaKnows)
    {
        return It.frs(
            // new static()
            () -> opt(clsRefPsi)
                .flt(ref -> ref.getText().equals("static")).itr()
                .fop(ref -> Opt.fst(
                    () -> ctx.getSelfType(),
                    () -> Tls.findParent(ref, PhpClass.class, a -> true)
                        .map(cls -> cls.getType())
                )),
            // new SomeCls(), $someObj->someField
            () -> opt(clsRefPsi)
                // don't allow IDEA to resolve $someCls::doSomething(), since it loses static:: context
                .flt(ref -> !ref.getText().startsWith("$"))
                .flt(ref -> ideaKnows)
                .map(exp -> exp.getType()),
            // new $clsInAVar()
            () -> opt(clsRefPsi)
                .fap(ref -> ctx.findExprType(ref))
                .fap(t -> t.clsRefType)
        );
    }

    public It<PhpType> resolveClassReference(PsiPolyVariantReference poly, PhpExpression clsRefPsi)
    {
        return resolveAnyClassReference(clsRefPsi, It(poly.multiResolve(false)).has());
    }

    public It<PhpType> resolveClassReferenceFromMember(MemberReference memRef)
    {
        return resolveAnyClassReference(memRef.getClassReference(), It(memRef.multiResolve(false)).has());
    }

    private It<DeepType> resolveNew(NewExpression newExp)
    {
        return opt(newExp.getClassReference())
            .fap(cls -> resolveClassReference(cls, cls))
            .map(ideaType -> {
                IExprCtx ctorArgs = ctx.subCtxDirect(newExp);
                return DeepType.makeNew(newExp, ctorArgs, ideaType);
            });
    }

    public It<DeepType> resolve(PsiElement expr)
    {
        return It.cnc(It.non()
            , Tls.cast(TernaryExpressionImpl.class, expr)
                .fap(tern -> It.cnc(
                    findPsiExprType(tern.getTrueVariant()),
                    findPsiExprType(tern.getFalseVariant())
                ))
            , Tls.cast(BinaryExpressionImpl.class, expr)
                .fap(bin -> opt(bin.getOperation())
                    .flt(op -> op.getText().equals("??") || op.getText().equals("?:"))
                    .fap(op -> It.cnc(
                        findPsiExprType(bin.getLeftOperand()),
                        findPsiExprType(bin.getRightOperand())
                    )))
            , Tls.cast(BinaryExpressionImpl.class, expr)
                .fap(bin -> opt(bin.getOperation())
                    .flt(op -> op.getText().equals("-")
                        || op.getText().equals("*") || op.getText().equals("/")
                        || op.getText().equals("%") || op.getText().equals("**")
                    )
                    .fap(op -> {
                        DeepType type = new DeepType(bin, PhpType.NUMBER);
                        type.isNumber = true;
                        return list(type);
                    }))
            , Tls.cast(BinaryExpressionImpl.class, expr)
                .fap(bin -> opt(bin.getOperation())
                    .flt(op -> op.getText().equals("+"))
                    .fap(op -> {
                        It<DeepType> tit = It.cnc(
                            findPsiExprType(bin.getLeftOperand()),
                            findPsiExprType(bin.getRightOperand())
                        );
                        Mutable<Boolean> isNum = new Mutable<>(false);
                        return tit.map(t -> {
                            isNum.set(isNum.get() || t.isNumber);
                            return isNum.get() ? new DeepType(bin, PhpType.NUMBER) : t;
                        });
                    }))
            , Tls.cast(BinaryExpressionImpl.class, expr)
                .fap(bin -> opt(bin.getOperation())
                    .flt(op -> op.getText().equals("."))
                    .fap(op -> {
                        It<DeepType> lmt = findPsiExprType(bin.getLeftOperand());
                        It<DeepType> rmt = findPsiExprType(bin.getRightOperand());
                        @Nullable String unescaped = opt(Mt.getStringValueSt(lmt))
                            .fop(lstr -> opt(Mt.getStringValueSt(rmt))
                                .map(rstr -> lstr + rstr))
                            .map(ccted -> StringEscapeUtils.unescapeJava(ccted)) // PHP ~ java
                            .def(null);
                        DeepType type = new DeepType(bin, PhpType.STRING, unescaped);
                        return list(type);
                    }))
            , Tls.cast(NewExpressionImpl.class, expr)
                .fap(newExp -> resolveNew(newExp))
        );
    }

}
