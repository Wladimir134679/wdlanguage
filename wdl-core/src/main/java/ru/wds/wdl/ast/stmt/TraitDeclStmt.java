package ru.wds.wdl.ast.stmt;

import ru.wds.wdl.ast.Fragment;
import ru.wds.wdl.ast.expr.Annotations;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.source.Span;

import java.util.List;
import java.util.Objects;

/**
 * Объявление трейта: {@code trait Counted(count = 0, limit) { def inc() { count += 1 } }}.
 * <p>
 * Трейт устроен как класс, у которого забрали конструктор: заголовок перечисляет поля,
 * тело перечисляет методы. Отличие ровно одно, и оно определяет всё остальное —
 * <b>и поле, и метод здесь бывают требованием</b>:
 * <ul>
 *   <li>{@code count = 0} в заголовке — поле с готовым значением, класс о нём не заботится;</li>
 *   <li>{@code limit} в заголовке — требование: класс обязан объявить поле {@code limit} сам;</li>
 *   <li>{@code def full() => ...} — готовый метод;</li>
 *   <li>{@code def report()} — требование: класс обязан это уметь.</li>
 * </ul>
 * Одно правило на поля и на методы, потому что поле и метод в языке и так одно
 * и то же обращение. Обязательное объявляется без значения, вспомогательное —
 * со значением или с телом.
 * <p>
 * <b>{@link PropertyDecl Свойство} подчиняется тому же правилу, но с точностью
 * до аксессора</b>: {@code property size { def get() }} требует чтения и молчит
 * о записи, {@code property size { def get() def set(value) }} требует обоих.
 * Ради этого свойства в трейтах и заведены — требование выражается через
 * возможность, а не через способ хранения, поэтому его закрывает и обычное поле,
 * и вычисляемое свойство, и заменить одно другим можно, не сломав контракт.
 * <p>
 * Требования проверяются <b>при объявлении класса</b>, а не при вызове и не при
 * создании экземпляра: ради этого трейты и заведены — ошибка появляется там,
 * где сделана.
 * <p>
 * Ни родителя, ни подмешанных трейтов у трейта нет: он и есть то, что подмешивают.
 * Значений полей, зависящих от параметров создания, тоже нет — {@code count = 0}
 * вычисляется в области, где трейт объявлен, и заново на каждом создании.
 * <p>
 * Одно понятие вместо трёх: интерфейс — трейт из одних требований, абстрактный класс —
 * трейт с реализациями и полями, примесь — трейт без требований. Отдельных слов
 * {@code interface} и {@code abstract} в языке нет.
 */
public record TraitDeclStmt(
        String name,
        Span nameSpan,
        Annotations annotations,
        List<FunctionExpr.Param> params,
        List<FunctionExpr> methods,
        List<Requirement> requirements,
        List<PropertyDecl> properties,
        Span span) implements Stmt {

    /** Та же инструкция с другим интервалом: см. {@link DefDeclStmt#withSpan}. */
    public TraitDeclStmt withSpan(Span span) {
        return new TraitDeclStmt(name, nameSpan, annotations, params, methods, requirements,
                properties, span);
    }

    public TraitDeclStmt {
        Objects.requireNonNull(name, "name");
        annotations = annotations == null ? Annotations.NONE : annotations;
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(properties, "properties");
    }

    /**
     * Требуемый метод: {@code def report()} — имя и список параметров без тела.
     * <p>
     * Отдельный вид, а не {@link FunctionExpr} с пустым телом: функции без тела
     * в языке не существует, и заводить её ради одного места разбора значило бы
     * пускать {@code null} во все посетители дерева.
     * <p>
     * Параметры нужны целиком, а не одним числом: у требования проверяется
     * не только имя, но и то, сколько аргументов метод обязан принимать.
     *
     * @param variadic объявлен ли у требования остаток {@code *args}. Само имя остатка
     *                 требованию не нужно — оно живёт в теле метода, которого здесь нет;
     *                 а вот число аргументов от него зависит: с остатком верхней границы
     *                 у него нет
     * @param annotations данные, приписанные требованию. Значения у требования нет,
     *                 поэтому прочитать их из скрипта пока нечем; в дереве они лежат
     *                 затем же, зачем там лежит всё остальное, — их видят инструменты
     * @param nameSpan место имени в исходнике: то, к чему ведёт переход к объявлению
     *                 и что переименовывает переименование. Своего {@link FunctionExpr}
     *                 у требования нет — тела нет, а значит и функции, — поэтому место
     *                 имени хранится здесь
     * @param mirror   требуется ли зеркальный оператор: {@code mirror def `+`(left)}.
     *                 Признаком, а не мангленным именем, — {@code name} обязан отражать
     *                 текст, ровно как у {@link FunctionExpr}
     */
    public record Requirement(String name, Span nameSpan, Annotations annotations,
                              List<FunctionExpr.Param> params, boolean variadic,
                              boolean mirror, Span span) implements Fragment {

        public Requirement {
            nameSpan = nameSpan == null ? Span.NONE : nameSpan;
            annotations = annotations == null ? Annotations.NONE : annotations;
        }

        /** Обычное требование: {@code def report()}. */
        public Requirement(String name, Span nameSpan, List<FunctionExpr.Param> params,
                           boolean variadic, Span span) {
            this(name, nameSpan, Annotations.NONE, params, variadic, false, span);
        }
    }
}
