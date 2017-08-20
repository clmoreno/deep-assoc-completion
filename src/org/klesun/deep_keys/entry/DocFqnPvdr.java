package org.klesun.deep_keys.entry;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.PhpIcons;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.psi.elements.ArrayIndex;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression;
import com.jetbrains.php.lang.psi.elements.impl.ArrayAccessExpressionImpl;
import org.jetbrains.annotations.NotNull;
import org.klesun.deep_keys.DeepType;
import org.klesun.deep_keys.DeepTypeResolver;
import org.klesun.lang.Lang;
import org.klesun.lang.Opt;
import org.klesun.lang.Tls;

import java.util.*;

import static org.klesun.lang.Lang.L;
import static org.klesun.lang.Lang.list;
import static org.klesun.lang.Lang.opt;

/**
 * provides completion for class/functions inside a @param doc comment
 */
public class DocFqnPvdr extends CompletionProvider<CompletionParameters>
{
    private static LookupElement makeLookup(DeepType.Key keyEntry, Project project)
    {
        LookupElementBuilder result = LookupElementBuilder.create(keyEntry.name)
            .bold()
            .withIcon(PhpIcons.FIELD);

        if (keyEntry.types.size() > 0) {
            DeepType type = keyEntry.types.get(0);
            result = result.withTypeText(type.briefType.toString());
        } else {
            result = result.withTypeText("unknown");
        }

        return result;
    }

    private Opt<List<String>> extractTypedFqnPart(String docValue, Project project)
    {
        PhpIndex idx = PhpIndex.getInstance(project);
        return Opt.fst(list(opt(null)
            , Tls.regex(" *= *([A-Z][A-Za-z0-9_]+)::([a-zA-Z0-9_]*?)(IntellijIdeaRulezzz.*)?", docValue)
                // have to complete method
                .map(mtch -> {
                    PrefixMatcher clsMatcher = new CamelHumpMatcher(mtch.gat(0).unw());
                    PrefixMatcher metMatcher = new CamelHumpMatcher(mtch.gat(1).unw());
                    return L(idx.getAllClassNames(clsMatcher))
                        .map(n -> idx.getClassByName(n))
                        .fap(cls -> L(cls.getMethods())
                            .map(m -> m.getName())
                            .flt(p -> metMatcher.prefixMatches(p))
                            .map(f -> f + "()"));
                })
            , Tls.regex(" *= *([A-Z][A-Za-z0-9_]+?)(IntellijIdeaRulezzz.*)?", docValue)
                // have to complete class
                .fap(m -> m.gat(0))
                .map(CamelHumpMatcher::new)
                .map(p -> L(idx.getAllClassNames(p))
                    .map(cls -> cls + "::"))
        ));
    }

    @Override
    protected void addCompletions(@NotNull CompletionParameters parameters, ProcessingContext processingContext, @NotNull CompletionResultSet result)
    {
        opt(parameters.getPosition().getParent())
            .thn(literal -> extractTypedFqnPart(literal.getText(), literal.getProject())
                .thn(options -> L(options)
                    .map(LookupElementBuilder::create)
                    .fch(result::addElement))
                .els(() -> System.out.println("No FQN-s found with such partial name - " + literal.getText())));
    }
}