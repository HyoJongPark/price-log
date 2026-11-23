package pricelog.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import com.puppycrawl.tools.checkstyle.utils.TokenUtil;

/** Checks the written length of consecutive single-line annotations on a type. */
public class AnnotationLengthOrderCheck extends AbstractCheck {

    @Override
    public int[] getDefaultTokens() {
        return new int[] {TokenTypes.ANNOTATION};
    }

    @Override
    public int[] getAcceptableTokens() {
        return getDefaultTokens();
    }

    @Override
    public int[] getRequiredTokens() {
        return getDefaultTokens();
    }

    @Override
    public void visitToken(DetailAST annotation) {
        DetailAST modifiers = annotation.getParent();
        DetailAST previous = annotation.getPreviousSibling();
        if (modifiers.getType() != TokenTypes.MODIFIERS
            || !TokenUtil.isTypeDeclaration(modifiers.getParent().getType())
            || previous == null || previous.getType() != TokenTypes.ANNOTATION) {
            return;
        }

        int length = singleLineLength(annotation);
        if (length > 0 && length < singleLineLength(previous)) {
            log(annotation, "annotation.length.order");
        }
    }

    private static int singleLineLength(DetailAST annotation) {
        DetailAST end = annotation.getLastChild();
        while (end.getLastChild() != null) {
            end = end.getLastChild();
        }
        if (end.getLineNo() != annotation.getLineNo()) {
            return 0;
        }
        return end.getColumnNo() + end.getText().length() - annotation.getColumnNo();
    }
}
