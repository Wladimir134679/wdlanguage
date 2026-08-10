package ru.wds.wdl.resolve;

import ru.wds.wdl.ast.Program;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.Stmt;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.diagnostic.Diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Резолвер: решает, какие объявления типов можно выполнить до первой инструкции
 * и в каком порядке.
 * <p>
 * Это всё, что о классах известно из текста. Форму класса собирает {@link Linker}
 * при выполнении объявления — тогда, когда родитель и трейты уже стали значениями.
 * Раньше форма строилась здесь, и ради родителя из чужого файла резолвер загружал
 * модули: скрипт не запускался, если модуля ещё нет на диске, и платил разбором
 * за импорты, до которых выполнение могло не дойти. Теперь резолвер о модулях
 * не знает вовсе и файлов не читает.
 * <p>
 * <b>Что он делает.</b> Смотрит на имена, которыми классы верхнего уровня ссылаются
 * друг на друга внутри этого файла, и расставляет объявления так, чтобы родитель
 * и подмешанные трейты выполнялись раньше потомка. Отсюда свобода порядка:
 * {@code class Circle : Shape} можно написать выше самого {@code Shape}. Тем же
 * обходом виден круг в наследовании — единственная ошибка, которую эта стадия
 * ещё может назвать.
 * <p>
 * <b>Чего он не делает.</b> Класс, чей родитель или трейт в этом файле не объявлен,
 * до выполнения не трогается совсем: имя придёт из {@code import} или от приложения,
 * и связать его заранее нечем. Такой класс появляется на своей строке — как класс
 * внутри блока, — а «неизвестный класс», «это трейт, а не класс», требования трейтов
 * и число аргументов родителю называет выполнение.
 * <p>
 * Ошибки копятся в тот же {@link Diagnostics}, что у лексера и парсера. Результат
 * возвращается всегда — как и дерево от парсера.
 */
public final class Resolver {

    private final Diagnostics diagnostics;
    /** Типы верхнего уровня этого файла: имя → объявление. */
    private final Map<String, Stmt> types = new HashMap<>();
    /** Уже принятое решение по классу — заодно и защита от повторного обхода. */
    private final Map<ClassDeclStmt, Boolean> decided = new IdentityHashMap<>();
    /** Классы, по которым обход идёт прямо сейчас, — по ним и виден круг. */
    private final Set<ClassDeclStmt> walking = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<String> chain = new ArrayDeque<>();
    private final List<Stmt> plan = new ArrayList<>();

    private Resolver(Diagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Составляет план выполнения объявлений верхнего уровня.
     * <p>
     * Трейты идут первыми и все: трейт ни от чего не зависит — ни родителя,
     * ни примесей у него нет, — поэтому порядок их объявления не значит ничего.
     * Классы вторыми, обходом в глубину по имени родителя.
     */
    public static Resolution resolve(Program program, Diagnostics diagnostics) {
        Resolver resolver = new Resolver(diagnostics);
        program.statements().forEach(resolver::declare);
        for (Stmt statement : program.statements()) {
            if (statement instanceof TraitDeclStmt trait) {
                resolver.plan.add(trait);
            }
        }
        for (Stmt statement : program.statements()) {
            if (statement instanceof ClassDeclStmt klass) {
                resolver.schedule(klass);
            }
        }
        return new Resolution(resolver.plan);
    }

    private void declare(Stmt statement) {
        // Одноимённых объявлений в файле бывает два: имя типа — обычное имя, и второе
        // объявление перекрывает первое, как второе 'fun' с тем же именем. Побеждает
        // последнее — им же и связывается всё, что ссылается на это имя.
        switch (statement) {
            case ClassDeclStmt klass -> types.put(klass.name(), klass);
            case TraitDeclStmt trait -> types.put(trait.name(), trait);
            default -> { }
        }
    }

    /**
     * Ставит класс в план, если его зависимости в этом файле есть и сами запланированы.
     *
     * @return {@code true}, если класс будет объявлен до первой инструкции
     */
    private boolean schedule(ClassDeclStmt klass) {
        Boolean known = decided.get(klass);
        if (known != null) {
            return known;
        }
        if (!walking.add(klass)) {
            // Круг в наследовании: A наследует B, B наследует A. Никакого порядка,
            // при котором родитель раньше потомка, тут не существует — и при
            // выполнении не существовало бы тоже.
            diagnostics.error(klass.nameSpan(), "циклическое наследование: "
                    + String.join(" → ", chain) + " → " + klass.name());
            return false;
        }

        chain.addLast(klass.name());
        try {
            boolean ready = parentReady(klass) && traitsReady(klass);
            decided.put(klass, ready);
            if (ready) {
                plan.add(klass);
            }
            return ready;
        } finally {
            chain.removeLast();
            walking.remove(klass);
        }
    }

    /**
     * Родитель этого файла и сам запланирован.
     * <p>
     * Квалифицированное имя ({@code m.Shape}) — всегда «нет»: слева от точки стоит
     * имя импорта, а модуль выполняется своей инструкцией, до которой очередь
     * ещё не дошла.
     */
    private boolean parentReady(ClassDeclStmt klass) {
        ClassDeclStmt.Superclass parent = klass.parent();
        if (parent == null) {
            return true;
        }
        if (parent.alias() != null) {
            return false;
        }
        // Трейт в позиции родителя — ошибка, но назовёт её выполнение: там видно
        // значение, а не только имя, и сообщение получается одно на все случаи.
        return types.get(parent.name()) instanceof ClassDeclStmt declared && schedule(declared);
    }

    /** Все подмешанные трейты объявлены в этом файле. Трейты планируются все, проверять их нечего. */
    private boolean traitsReady(ClassDeclStmt klass) {
        for (ClassDeclStmt.TraitRef reference : klass.traits()) {
            if (reference.alias() != null || !(types.get(reference.name()) instanceof TraitDeclStmt)) {
                return false;
            }
        }
        return true;
    }
}
