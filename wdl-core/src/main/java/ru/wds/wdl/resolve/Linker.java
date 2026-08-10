package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.value.Arity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Связывание: сборка формы класса из объявления, родителя и трейтов — со всеми
 * проверками, которые для этого нужны.
 * <p>
 * Стадия отдельная от разбора, и это главное решение всей системы модулей.
 * Родитель приходит из другого файла, а чужой файл выполняется своей инструкцией
 * {@code import} — значит, до выполнения его формы просто не существует. Раньше
 * ради неё приходилось загружать модули заранее, и скрипт не запускался, если
 * модуля ещё нет на диске. Теперь связывание живёт там, где выполняется
 * объявление класса, и загружать заранее нечего.
 * <p>
 * <b>Момент проверки — объявление класса, а не создание экземпляра.</b> Разница
 * с {@code abc.ABCMeta} в Python, где забытый метод всплывает при {@code new},
 * тут принципиальная: классы файла объявлены на его верхнем уровне, поэтому
 * к концу инструкции {@code import} всё, что модуль отдаёт, уже собрано и проверено.
 * Позже ошибке взяться неоткуда.
 * <p>
 * <b>Кэш обязателен, а не желателен.</b> {@code is} сравнивает формы по ссылке,
 * а объявление класса внутри функции выполняется на каждый вызов. Без кэша два вызова
 * дали бы два разных класса с одинаковым содержимым, и {@code c is Point} врало бы
 * при совершенно правильном скрипте. Ключ — узел дерева вместе с родителем и трейтами:
 * если в этот раз родитель другой, то это и правда другой класс, и форма ему нужна своя.
 * <p>
 * Экземпляр живёт ровно столько, сколько запуск: формы держат ссылки на значения
 * родителей, а те у каждого запуска свои. Потокобезопасности нет и не требуется —
 * один запуск идёт в одном потоке.
 */
public final class Linker {

    private final Map<ClassDeclStmt, List<Linked>> classes = new IdentityHashMap<>();
    private final Map<TraitDeclStmt, TraitShape> traits = new IdentityHashMap<>();

    /** Пустой линкер: по одному на запуск, вместе с его контекстом выполнения. */
    public Linker() {
    }

    /** Уже собранная связка: чем связывали и что получилось. */
    private record Linked(ClassShape parent, List<TraitShape> traits, ClassShape shape) {

        boolean sameAs(ClassShape otherParent, List<TraitShape> otherTraits) {
            if (parent != otherParent || traits.size() != otherTraits.size()) {
                return false;
            }
            for (int i = 0; i < traits.size(); i++) {
                if (traits.get(i) != otherTraits.get(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Форма трейта.
     * <p>
     * Связывать тут нечего — у трейта нет ни родителя, ни примесей, — поэтому форма
     * строится из одного объявления. Кэш нужен по той же причине, что у класса:
     * трейт, объявленный внутри функции, обязан оставаться одним трейтом для {@code is}.
     */
    public TraitShape traitShape(TraitDeclStmt declaration) {
        return traits.computeIfAbsent(declaration, TraitShape::new);
    }

    /**
     * Форма класса: готовая из кэша или собранная и проверенная сейчас.
     *
     * @param parent форма родителя или {@code null}
     * @throws LinkError если родителю передано не столько аргументов или не выполнено
     *                   требование трейта
     */
    public ClassShape classShape(ClassDeclStmt declaration, ClassShape parent,
                                 List<TraitShape> mixins) {
        List<Linked> known = classes.computeIfAbsent(declaration, key -> new ArrayList<>(1));
        for (Linked linked : known) {
            if (linked.sameAs(parent, mixins)) {
                return linked.shape();
            }
        }

        ClassShape shape = new ClassShape(declaration, parent, mixins);
        // Проверки до записи в кэш: неудачная связка запоминаться не должна, иначе
        // второе выполнение той же строки промолчало бы.
        checkParentArguments(shape);
        checkRequirements(shape);
        known.add(new Linked(parent, List.copyOf(mixins), shape));
        return shape;
    }

    /**
     * Число аргументов родителю.
     * <p>
     * Сколько их написано, известно из текста; сколько принимает родитель — из его
     * формы, а она есть только здесь. Поэтому проверка и стоит на связывании,
     * а не при каждом создании экземпляра: заголовок родителя между двумя {@code new}
     * не меняется.
     */
    private static void checkParentArguments(ClassShape shape) {
        ClassShape parent = shape.parent();
        ClassDeclStmt.Superclass reference = shape.declaration().parent();
        if (parent == null || reference == null) {
            return;
        }
        int given = reference.arguments().size();
        if (!parent.arity().accepts(given)) {
            throw new LinkError(reference.span(), "класс '" + shape.name() + "' передаёт родителю '"
                    + parent.name() + "' " + given + " аргументов, а '" + parent.name()
                    + "' принимает " + parent.arity().describeArguments());
        }
    }

    /**
     * Требования трейтов — то единственное, чего не даёт утиная типизация, и то,
     * ради чего трейты заведены.
     * <p>
     * Требование считается выполненным, если нужное поле или метод есть у самого
     * класса, у его предка или у другого трейта: смотрим в уже собранные плоские
     * таблицы, поэтому порядок {@code with} на результат не влияет.
     * <p>
     * Поле закрывает только поле, метод — только метод: у поля нечего проверять
     * на число аргументов, а перекрытие метода полем — уже другая история,
     * и это работа линтера.
     */
    private static void checkRequirements(ClassShape shape) {
        for (TraitShape trait : shape.traits()) {
            for (FunctionExpr.Param required : trait.requiredFields()) {
                if (!shape.fields().containsKey(required.name())) {
                    throw new LinkError(shape.declaration().nameSpan(), unmet(shape, trait)
                            + "нет поля '" + required.name() + "'. Объявите его в заголовке класса");
                }
            }
            for (TraitDeclStmt.Requirement required : trait.requiredMethods()) {
                MethodSlot provided = shape.methods().get(required.name());
                if (provided == null) {
                    throw new LinkError(shape.declaration().nameSpan(), unmet(shape, trait)
                            + "нет метода '" + required.name() + "'");
                }
                Arity expected = ClassShape.arityOf(required.params());
                Arity actual = ClassShape.arityOf(provided.declaration().params());
                if (!actual.accepts(expected.min()) || !actual.accepts(expected.max())) {
                    throw new LinkError(shape.declaration().nameSpan(), unmet(shape, trait)
                            + "метод '" + required.name() + "' должен принимать "
                            + expected.describeArguments() + ", а принимает "
                            + actual.describeArguments());
                }
            }
        }
    }

    private static String unmet(ClassShape shape, TraitShape trait) {
        return "класс '" + shape.name() + "' не выполняет требование трейта '"
                + trait.name() + "': ";
    }
}
