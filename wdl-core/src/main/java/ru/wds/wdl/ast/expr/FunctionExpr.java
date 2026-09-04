package ru.wds.wdl.ast.expr;

import ru.wds.wdl.ast.op.Overloads;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.source.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Функция как выражение: {@code def(a, b) => a + b}.
 * <p>
 * Узел один и на анонимную функцию, и на объявление — {@code def имя(...)} это
 * {@link ru.wds.wdl.ast.stmt.DefDeclStmt} с этим самым узлом внутри. Логика создания
 * значения-функции остаётся в одном месте, а разница между двумя формами ровно та,
 * какая есть на самом деле: объявление ещё и заводит имя.
 * <p>
 * <b>Имя хранится и у анонимной, если его удалось узнать.</b> У {@code f = def(a) => a}
 * узел анонимный, но сообщение «функция 'f' принимает ровно 1 аргумент» полезнее, чем
 * «функция 'def' ...», поэтому имя цели простого присваивания подставляется при разборе.
 * Это только для диагностики: на поиск имени во время выполнения оно не влияет.
 * Отсюда и отдельный признак {@link #anonymous()}: по одному лишь {@code name != null}
 * «написали ли {@code def имя}» уже не узнать, а метаданным декоратора
 * ({@code sys.meta}) нужен именно этот факт, а не то, что подставили для сообщений.
 * <p>
 * Тело — {@link Stmt}, и да, это делает зависимость пакетов взаимной: инструкции
 * ссылаются на выражения, а функция-выражение — на инструкции. Иначе в языке
 * с функциональными литералами не бывает: тело функции состоит из инструкций,
 * а сама функция — значение.
 *
 * @param name      имя для диагностики или {@code null}, если узнать его неоткуда
 * @param anonymous записана ли функция без имени. Не то же, что {@code name == null}:
 *                  у {@code f = def(a) => a} имя подставлено, а функция анонимна
 * @param modifiers слова перед {@code def}: сейчас там бывает только
 *                  {@link Modifier#SYNCHRONIZED}. Набором, а не признаком, — почему
 *                  именно так, разобрано в {@link Modifier}
 * @param annotations данные, приписанные объявлению: {@code @{route: "/users"}}.
 *                  Лежат в самом объявлении, а не в обёртке над инструкцией, потому
 *                  что нужны и методу класса, до которого {@code DecoratedStmt}
 *                  не дотягивается. У анонимной функции их не бывает — привязывать
 *                  их там не к чему, и это проверяется здесь
 * @param params    параметры в порядке записи. <b>Только те, у которых есть позиция</b>:
 *                  остатки лежат отдельно, потому что этот список читают как «параметр
 *                  номер такой-то» — поле класса, значение по умолчанию, аргумент родителю
 * @param rest      остаточный параметр {@code *args} или {@code null}
 * @param namedRest именованный остаток {@code **named} или {@code null}
 * @param body      тело: блок, одиночная инструкция или {@code return} из стрелки
 * @param style     как тело было записано — {@link BodyStyle}
 * @param span      место в исходнике: от первого слова заголовка до конца тела
 */
public record FunctionExpr(String name, boolean anonymous, Set<Modifier> modifiers,
                           Annotations annotations, List<Param> params, Rest rest,
                           Rest namedRest, Stmt body, BodyStyle style, Span span)
        implements Expr {

    public FunctionExpr {
        modifiers = modifiers == null || modifiers.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(modifiers));
        annotations = annotations == null ? Annotations.NONE : annotations;
        params = List.copyOf(Objects.requireNonNull(params, "params"));
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(span, "span");
        if (anonymous && annotations.written()) {
            // Запрет '@{a: 1} f = def (x) => x' получается по построению: проверять
            // его в каждом посетителе не придётся.
            throw new IllegalArgumentException("у анонимной функции нет объявления, "
                    + "к которому можно привязать аннотации: " + span);
        }
    }

    /** Объявление под именем: {@code def f()}, метод, фабрика. */
    public FunctionExpr(String name, Set<Modifier> modifiers, List<Param> params,
                        Rest rest, Rest namedRest, Stmt body, BodyStyle style, Span span) {
        this(name, name == null, modifiers, Annotations.NONE, params, rest, namedRest,
                body, style, span);
    }

    /** То же с аннотациями: {@code @{a: 1} def f()}. */
    public FunctionExpr(String name, Set<Modifier> modifiers, Annotations annotations,
                        List<Param> params, Rest rest, Rest namedRest, Stmt body,
                        BodyStyle style, Span span) {
        this(name, name == null, modifiers, annotations, params, rest, namedRest,
                body, style, span);
    }

    /** Помечена ли функция {@code synchronized} — вопрос, который задают чаще всего. */
    public boolean isSynchronized() {
        return modifiers.contains(Modifier.SYNCHRONIZED);
    }

    /** Помечена ли функция {@code mirror}: оператор, получатель которого стоит справа. */
    public boolean isMirror() {
        return modifiers.contains(Modifier.MIRROR);
    }

    /**
     * Имя, под которым член лежит в таблице методов.
     * <p>
     * Обычно это само имя. Мангленное оно у двух видов операторов: у зеркального
     * ({@code `+`} и {@code mirror `+`} — разные члены одного класса) и у унарного
     * ({@code def `-`()} рядом с {@code def `-`(right)} — тоже разные). Манглинг живёт
     * одной функцией на весь проект ({@link Overloads#key}) именно поэтому: разойтись
     * двум спискам правил не с чем, а {@link #name()} остаётся честным — таким,
     * как в тексте.
     */
    public String memberName() {
        return Overloads.key(name, isMirror(), params.isEmpty() && rest == null);
    }

    /** Собирает ли функция лишние аргументы — то есть безгранично ли число аргументов. */
    public boolean isVariadic() {
        return rest != null;
    }

    /**
     * Остаточный параметр: {@code *args} и {@code **named}.
     * <p>
     * Отдельный тип, а не {@link Param} с пометкой, по двум причинам сразу.
     * Первая — у остатка не бывает значения по умолчанию: пустой массив и пустой объект
     * и есть его дефолт, а поле {@code defaultValue} пришлось бы всё время проверять
     * на {@code null} с оговоркой «здесь его быть не может». Вторая важнее: остаток
     * не занимает позиции, а {@link #params()} читается как список позиций — номер
     * параметра там значит номер поля, номер аргумента и порядок связывания.
     * <p>
     * Имя остатка — обычная локальная переменная, а не имя параметра: {@code f(args: 1)}
     * не задаёт {@code *args} целиком. Поэтому в контракт вызова
     * ({@code value.Signature}) оно попадает отдельно от имён параметров и в поиске
     * по имени не участвует.
     *
     * @param name имя переменной, в которую соберётся остаток
     * @param span место имени в исходнике
     */
    public record Rest(String name, Span span) {

        public Rest {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Параметр функции — имя и, если оно есть, значение по умолчанию.
     * <p>
     * Отдельный тип, а не просто строка, из-за места в исходнике: сообщение о дубликате
     * должно указывать на второе вхождение, а не на всю функцию.
     * <p>
     * <b>Значение по умолчанию хранится деревом, а не вычисленным значением.</b> Считается
     * оно при каждом вызове ({@code runtime.UserFunction}), и это отличие от Python
     * намеренное: там дефолт вычисляется один раз при объявлении и живёт с функцией,
     * отчего общий изменяемый список копит значения между вызовами. Массивы и объекты
     * здесь тоже изменяемые, так что снимок значения принёс бы ту же ловушку — да ещё
     * и в виде изменяемого состояния внутри узла дерева, который обязан оставаться данными.
     * <p>
     * <b>Параметр-дырка</b> {@code _} — тот же {@code Param} с именем {@link #HOLE},
     * а не отдельный вид. Отдельным он был бы, если бы у него было своё поведение;
     * его нет: дырка так же занимает позицию, так же считается в
     * {@linkplain ru.wds.wdl.value.Arity числе аргументов} и так же принимает значение.
     * Отличие ровно одно и целиком отрицательное — имени у неё нет, поэтому в область
     * она не кладётся, полем класса не становится и по имени её не передать.
     * Спутать дырку с обычным именем нельзя по построению: {@code _} лексер отдаёт
     * {@link ru.wds.wdl.lexer.TokenType#HOLE}, и в {@code Param} такое имя может
     * поставить только разбор дырки.
     *
     * <b>Аннотации параметра лежат здесь же.</b> Заголовок класса — это его поля,
     * поэтому {@code class User(@{column: "id"} id)} и {@code def total(@{min: 0} price)}
     * — одна и та же запись в одном и том же узле; второго места для «данных о поле»
     * язык не заводит.
     *
     * @param name         имя параметра или {@link #HOLE} у дырки
     * @param annotations  данные, приписанные параметру, или {@link Annotations#NONE}
     * @param defaultValue значение по умолчанию или {@code null}, если параметр обязателен
     * @param span         место имени в исходнике
     */
    public record Param(String name, Annotations annotations, Expr defaultValue, Span span) {

        /** Имя параметра-дырки. Обычным именем оно быть не может — см. javadoc записи. */
        public static final String HOLE = "_";

        public Param {
            Objects.requireNonNull(name, "name");
            annotations = annotations == null ? Annotations.NONE : annotations;
            Objects.requireNonNull(span, "span");
        }

        /** Параметр без аннотаций: {@code def f(a, b = 10)}. */
        public Param(String name, Expr defaultValue, Span span) {
            this(name, Annotations.NONE, defaultValue, span);
        }

        /** Обязательный параметр — без значения по умолчанию. */
        public Param(String name, Span span) {
            this(name, Annotations.NONE, null, span);
        }

        /** Параметр-дырка: {@code def onClick(_, event)}. Значения по умолчанию у неё нет. */
        public static Param hole(Span span) {
            return new Param(HOLE, Annotations.NONE, null, span);
        }

        /** Дырка ли это — то есть надо ли забыть значение сразу после связывания. */
        public boolean isHole() {
            return HOLE.equals(name);
        }

        public boolean hasDefault() {
            return defaultValue != null;
        }

        @Override
        public String toString() {
            return defaultValue != null ? name + " = " + defaultValue : name;
        }
    }

    /** Имя для сообщений: у анонимной — просто {@code def}. */
    public String title() {
        return name != null ? name : "def";
    }

    /**
     * Как имя записано в исходнике: у оператора — в обратных кавычках.
     * <p>
     * Нужно тем, кто печатает дерево обратно — дамперу и форматтеру: {@code (def +)}
     * и {@code (def `+`)} это разные записи, и вторая — та, которую человек написал.
     * Флага в узле для этого не заводится: имя оператора не бывает обычным именем,
     * лексер таких не выдаёт, поэтому ответ даёт сам список — {@link Overloads}.
     */
    public String writtenName() {
        if (name == null) {
            return "def";
        }
        return Overloads.isBinary(name) || Overloads.isUnary(name) ? "`" + name + "`" : name;
    }

    @Override
    public String toString() {
        List<String> header = new ArrayList<>(params.size() + 2);
        params.forEach(param -> header.add(param.toString()));
        if (rest != null) {
            header.add("*" + rest.name());
        }
        if (namedRest != null) {
            header.add("**" + namedRest.name());
        }
        return modifiers.stream().map(Modifier::text).collect(Collectors.joining(" ", "", " "))
                .stripLeading()
                + "def " + (name != null ? name : "")
                + header.stream().collect(Collectors.joining(", ", "(", ")"));
    }
}
