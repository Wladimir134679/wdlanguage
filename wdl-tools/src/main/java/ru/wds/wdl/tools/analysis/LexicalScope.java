package ru.wds.wdl.tools.analysis;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Область видимости файла: что в ней объявлено и где она начинается и кончается.
 * <p>
 * Строится по тексту, а не по выполнению, поэтому область здесь одна на конструкцию,
 * а не одна на проход: у цикла при выполнении своё окружение на каждой итерации,
 * а в тексте — один набор имён. Для вопросов редактора («что видно в этой точке»,
 * «где объявлено это имя») разница не существенна: имена в каждом проходе те же.
 * <p>
 * Изменяема только во время сборки — {@link ScopeBuilder} наполняет её и запечатывает;
 * наружу отдаются неизменяемые списки.
 */
public final class LexicalScope {

    private final ScopeKind kind;
    private final Span span;
    private final Node owner;
    private final LexicalScope parent;
    private final List<LexicalScope> children = new ArrayList<>(2);
    private final List<Symbol> symbols = new ArrayList<>(4);

    LexicalScope(ScopeKind kind, Span span, Node owner, LexicalScope parent) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.span = Objects.requireNonNull(span, "span");
        this.owner = owner;
        this.parent = parent;
        if (parent != null) {
            parent.children.add(this);
        }
    }

    public ScopeKind kind() {
        return kind;
    }

    /** Интервал, внутри которого область действует. */
    public Span span() {
        return span;
    }

    /** Узел, породивший область: функция, блок, класс. У файла — {@code Program}. */
    public Node owner() {
        return owner;
    }

    /** Внешняя область или {@code null} у файла. */
    public LexicalScope parent() {
        return parent;
    }

    public List<LexicalScope> children() {
        return Collections.unmodifiableList(children);
    }

    /** Имена, объявленные здесь, в порядке появления в тексте. */
    public List<Symbol> symbols() {
        return Collections.unmodifiableList(symbols);
    }

    void add(Symbol symbol) {
        symbols.add(symbol);
    }

    /**
     * Заменяет символы во всём поддереве — тем же по смыслу, но дополненным.
     * <p>
     * Так к ним прирастает документация из комментариев. Именно заменой на месте,
     * а не копией списка снаружи: символ обязан быть <b>одним объектом</b> и в области,
     * и в общем списке файла, иначе {@code usages} перестаёт находить употребления —
     * разрешение вернёт символ из области, а сравнят его с документированной копией.
     */
    void replaceSymbols(java.util.function.UnaryOperator<Symbol> mapping) {
        symbols.replaceAll(mapping);
        children.forEach(child -> child.replaceSymbols(mapping));
    }

    /** Все символы поддерева одним списком, в текстовом порядке. */
    List<Symbol> collectSymbols() {
        List<Symbol> found = new ArrayList<>(symbols);
        children.forEach(child -> found.addAll(child.collectSymbols()));
        found.sort(java.util.Comparator.comparingInt(symbol -> symbol.nameSpan().start()));
        return List.copyOf(found);
    }

    /**
     * Приводит символы в текстовый порядок: присваивания решаются в конце сборки
     * и приходят сюда позже объявлений, а читать список надо так, как написан файл.
     */
    void seal() {
        symbols.sort(java.util.Comparator.comparingInt(symbol -> symbol.nameSpan().start()));
        children.forEach(LexicalScope::seal);
    }

    /** Символ с этим именем, объявленный <b>в самой этой области</b>, или {@code null}. */
    public Symbol declared(String name) {
        // С конца: одно имя может быть объявлено дважды (второе присваивание не заводит
        // нового имени, а вот 'def f' после 'f = 1' — заводит), и сильнее последнее.
        for (int i = symbols.size() - 1; i >= 0; i--) {
            if (symbols.get(i).name().equals(name)) {
                return symbols.get(i);
            }
        }
        return null;
    }

    /** Накрывает ли область смещение. Границы полуоткрыты, как у всех интервалов. */
    public boolean covers(int offset) {
        return !span.isNone() && offset >= span.start() && offset < span.end();
    }

    /** Ближайшее тело типа отсюда наружу или {@code null}, если мы не внутри типа. */
    public LexicalScope enclosingType() {
        for (LexicalScope scope = this; scope != null; scope = scope.parent) {
            if (scope.kind == ScopeKind.CLASS_BODY || scope.kind == ScopeKind.TRAIT_BODY
                    || scope.kind == ScopeKind.EXTENSION_BODY) {
                return scope;
            }
        }
        return null;
    }

    /** Ближайшая граница вызова отсюда наружу, включая саму эту область. */
    public LexicalScope callBoundary() {
        for (LexicalScope scope = this; scope != null; scope = scope.parent) {
            if (scope.kind.isCallBoundary()) {
                return scope;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return kind.title() + " " + span + ", имён: " + symbols.size();
    }
}
