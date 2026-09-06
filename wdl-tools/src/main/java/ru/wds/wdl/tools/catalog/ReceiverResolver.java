package ru.wds.wdl.tools.catalog;

import ru.wds.wdl.ast.Node;
import ru.wds.wdl.ast.Nodes;
import ru.wds.wdl.ast.expr.AccessExpr;
import ru.wds.wdl.ast.expr.ArrayExpr;
import ru.wds.wdl.ast.expr.CallExpr;
import ru.wds.wdl.ast.expr.ErrorExpr;
import ru.wds.wdl.ast.expr.Expr;
import ru.wds.wdl.ast.expr.FunctionExpr;
import ru.wds.wdl.ast.expr.LiteralExpr;
import ru.wds.wdl.ast.expr.NewExpr;
import ru.wds.wdl.ast.expr.ObjectExpr;
import ru.wds.wdl.ast.expr.VariableExpr;
import ru.wds.wdl.ast.stmt.AssignStmt;
import ru.wds.wdl.ast.stmt.ClassDeclStmt;
import ru.wds.wdl.ast.stmt.ConstDeclStmt;
import ru.wds.wdl.ast.stmt.ImportStmt;
import ru.wds.wdl.ast.stmt.PropertyDecl;
import ru.wds.wdl.ast.stmt.TraitDeclStmt;
import ru.wds.wdl.tools.analysis.FileAnalysis;
import ru.wds.wdl.tools.analysis.Symbol;
import ru.wds.wdl.tools.analysis.SymbolKind;
import ru.wds.wdl.value.Arity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Безопасно устанавливает форму выражения по его AST.
 *
 * <p>Здесь нет вызовов интерпретатора, импорта модулей WDL или вычисления
 * пользовательских выражений. Любая неизвестная или неоднозначная ветка остаётся
 * {@link ReceiverType.Unknown}; это важнее широкого, но ложного completion.</p>
 */
public final class ReceiverResolver {

    private final FileAnalysis analysis;
    private final Catalog catalog;

    private ReceiverResolver(FileAnalysis analysis, Catalog catalog) {
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public static ReceiverResolver of(FileAnalysis analysis, Catalog catalog) {
        return new ReceiverResolver(analysis, catalog);
    }

    /** Форма выражения, достаточная для следующего доступа через точку. */
    public ReceiverType resolve(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        return switch (expression) {
            case LiteralExpr literal -> new ReceiverType.Builtin(literal.value().type(),
                    ReceiverType.Confidence.EXACT, "литерал");
            case ArrayExpr ignored -> new ReceiverType.Builtin(ru.wds.wdl.value.ValueType.ARRAY,
                    ReceiverType.Confidence.EXACT, "литерал массива");
            case ObjectExpr ignored -> new ReceiverType.Builtin(ru.wds.wdl.value.ValueType.OBJECT,
                    ReceiverType.Confidence.EXACT, "литерал объекта");
            case NewExpr creation -> instanceOf(resolve(creation.callee()), "new");
            case CallExpr call -> resolveCall(call);
            case VariableExpr variable -> resolveVariable(variable);
            case AccessExpr access -> resolveAccess(access);
            case ErrorExpr ignored -> new ReceiverType.Error("неполное выражение");
            default -> ReceiverType.unknown("форма выражения неизвестна без выполнения");
        };
    }

    /** Член известного доступа, если он описан метаданными. */
    public ResolvedMember member(AccessExpr access) {
        Objects.requireNonNull(access, "access");
        String name = access.literalKey();
        if (name == null) {
            return null;
        }
        ReceiverType target = resolve(access.target());
        if (target instanceof ReceiverType.Module module) {
            SymbolDescriptor descriptor = module.descriptor().get(name);
            return descriptor == null ? null : ResolvedMember.name(descriptor);
        }
        MemberDescriptor descriptor = MemberLookup.of(catalog).members(target).stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElse(null);
        return descriptor == null ? null : ResolvedMember.member(descriptor, originOf(target));
    }

    /** Дескриптор экспортируемого символа текущего файла без исполнения этого файла. */
    public SymbolDescriptor describe(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return switch (symbol.kind()) {
            case CLASS -> classDescriptor(symbol);
            case TRAIT -> traitDescriptor(symbol);
            default -> new SymbolDescriptor(symbol.name(), symbol.kind(), symbol.signature(),
                    symbol.documentation(), Origin.FILE);
        };
    }

    private ReceiverType resolveAccess(AccessExpr access) {
        String name = access.literalKey();
        if (name == null) {
            return ReceiverType.unknown("вычисляемый ключ");
        }
        ReceiverType target = resolve(access.target());
        if (target instanceof ReceiverType.Module module) {
            SymbolDescriptor descriptor = module.descriptor().get(name);
            return descriptor == null ? ReceiverType.unknown("неизвестное имя модуля")
                    : shapeOf(descriptor, ReceiverType.Confidence.EXACT, module.source());
        }
        MemberDescriptor member = MemberLookup.of(catalog).members(target).stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        return resultOf(target, member, "результат доступа не описан");
    }

    private ReceiverType resolveCall(CallExpr call) {
        if (!(call.callee() instanceof AccessExpr access)) {
            return ReceiverType.unknown("результат вызова не описан");
        }
        ReceiverType target = resolve(access.target());
        String name = access.literalKey();
        if (name == null || target instanceof ReceiverType.Module) {
            return ReceiverType.unknown("результат вызова не описан");
        }
        MemberDescriptor member = MemberLookup.of(catalog).members(target).stream()
                .filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        return resultOf(target, member, "результат вызова не описан");
    }

    private ReceiverType resultOf(ReceiverType receiver, MemberDescriptor member, String fallback) {
        if (member == null || member.resultClass() == null) {
            return ReceiverType.unknown(fallback);
        }
        SymbolDescriptor descriptor = classNamed(receiver, member.resultClass());
        return descriptor == null ? ReceiverType.unknown("класс результата не найден")
                : new ReceiverType.Class(descriptor, ReceiverType.ClassAccess.INSTANCE,
                ReceiverType.Confidence.EXACT, "метаданные результата");
    }

    private SymbolDescriptor classNamed(ReceiverType receiver, String name) {
        if (receiver instanceof ReceiverType.Class type && type.descriptor().name().equals(name)) {
            return type.descriptor();
        }
        SymbolDescriptor root = catalog.root(name);
        if (root != null && root.kind() == SymbolKind.CLASS) {
            return root;
        }
        for (String key : catalog.moduleKeys()) {
            ModuleDescriptor module = catalog.module(key);
            SymbolDescriptor candidate = module == null ? null : module.get(name);
            if (candidate != null && candidate.kind() == SymbolKind.CLASS) {
                return candidate;
            }
        }
        return null;
    }

    private ReceiverType resolveVariable(VariableExpr variable) {
        Symbol symbol = analysis.resolve(variable.span().start()).orElse(null);
        if (symbol != null) {
            return switch (symbol.kind()) {
                case MODULE -> moduleOf(symbol);
                case CLASS -> new ReceiverType.Class(classDescriptor(symbol),
                        ReceiverType.ClassAccess.STATIC, ReceiverType.Confidence.EXACT,
                        "локальный класс");
                case TRAIT -> new ReceiverType.Trait(traitDescriptor(symbol),
                        ReceiverType.Confidence.EXACT, "локальный трейт");
                case CONSTANT -> valueOfConstant(symbol);
                case VARIABLE, LOOP_VARIABLE, CATCH_VARIABLE, RESOURCE -> valueOfVariable(symbol,
                        variable.span().start());
                default -> ReceiverType.unknown("локальное имя без известного значения");
            };
        }
        SymbolDescriptor descriptor = catalog.root(variable.name());
        return descriptor == null ? ReceiverType.unknown("неизвестное имя")
                : shapeOf(descriptor, ReceiverType.Confidence.EXACT, "каталог");
    }

    private ReceiverType moduleOf(Symbol symbol) {
        if (!(symbol.declaration() instanceof ImportStmt imported)) {
            return ReceiverType.unknown("неизвестный импорт");
        }
        ModuleDescriptor module = catalog.module(imported.path());
        return module == null ? ReceiverType.unknown("модуль не найден")
                : new ReceiverType.Module(module, ReceiverType.Confidence.EXACT, "импорт");
    }

    private ReceiverType valueOfConstant(Symbol symbol) {
        if (!(symbol.declaration() instanceof ConstDeclStmt constant)) {
            return ReceiverType.unknown("константа без инициализатора");
        }
        return inferred(resolve(constant.value()), "константа");
    }

    /**
     * Присваивание считается только когда оно единственное для того же символа до
     * точки запроса. Проверка через {@link FileAnalysis#resolve(int)} не позволяет
     * одноимённой переменной из соседней области подменить значение.
     */
    private ReceiverType valueOfVariable(Symbol symbol, int useOffset) {
        List<AssignStmt> writes = new ArrayList<>();
        Nodes.walk(analysis.program(), node -> {
            if (!(node instanceof AssignStmt assignment)
                    || assignment.span().start() >= useOffset
                    || !(assignment.target() instanceof VariableExpr target)) {
                return;
            }
            Symbol written = analysis.resolve(target.span().start()).orElse(null);
            if (symbol.equals(written)) {
                writes.add(assignment);
            }
        });
        if (writes.size() != 1) {
            return ReceiverType.unknown(writes.isEmpty()
                    ? "переменная без видимого присваивания"
                    : "несколько присваиваний переменной");
        }
        return inferred(resolve(writes.getFirst().value()), "единственное присваивание");
    }

    private static ReceiverType inferred(ReceiverType type, String source) {
        return switch (type) {
            case ReceiverType.Builtin builtin -> new ReceiverType.Builtin(builtin.valueType(),
                    ReceiverType.Confidence.INFERRED, source);
            case ReceiverType.Class typeClass -> new ReceiverType.Class(typeClass.descriptor(),
                    typeClass.access(), ReceiverType.Confidence.INFERRED, source);
            case ReceiverType.Trait trait -> new ReceiverType.Trait(trait.descriptor(),
                    ReceiverType.Confidence.INFERRED, source);
            case ReceiverType.Module module -> new ReceiverType.Module(module.descriptor(),
                    ReceiverType.Confidence.INFERRED, source);
            case ReceiverType.Function function -> new ReceiverType.Function(function.descriptor(),
                    ReceiverType.Confidence.INFERRED, source);
            case ReceiverType.Unknown unknown -> unknown;
            case ReceiverType.Union union -> union;
            case ReceiverType.Error error -> error;
        };
    }

    private static ReceiverType instanceOf(ReceiverType type, String source) {
        return switch (type) {
            case ReceiverType.Class typeClass -> new ReceiverType.Class(typeClass.descriptor(),
                    ReceiverType.ClassAccess.INSTANCE, ReceiverType.Confidence.EXACT, source);
            case ReceiverType.Trait trait -> trait;
            default -> ReceiverType.unknown("new от неизвестного класса");
        };
    }

    private static ReceiverType shapeOf(SymbolDescriptor descriptor,
                                        ReceiverType.Confidence confidence, String source) {
        return switch (descriptor.kind()) {
            case CLASS -> new ReceiverType.Class(descriptor, ReceiverType.ClassAccess.STATIC,
                    confidence, source);
            case TRAIT -> new ReceiverType.Trait(descriptor, confidence, source);
            case MODULE -> ReceiverType.unknown("модуль без ключа");
            case FUNCTION, METHOD, FACTORY, REQUIREMENT -> new ReceiverType.Function(descriptor,
                    confidence, source);
            default -> ReceiverType.unknown("значение каталога без формы");
        };
    }

    private SymbolDescriptor classDescriptor(Symbol symbol) {
        if (!(symbol.declaration() instanceof ClassDeclStmt type)) {
            return new SymbolDescriptor(symbol.name(), SymbolKind.CLASS, symbol.signature(),
                    symbol.documentation(), Origin.FILE);
        }
        Map<String, MemberDescriptor> instance = new LinkedHashMap<>();
        for (FunctionExpr.Param field : type.params()) {
            instance.put(field.name(), MemberDescriptor.property(field.name(), false, null));
        }
        type.methods().forEach(method -> instance.put(method.memberName(), methodDescriptor(method)));
        type.properties().forEach(property -> instance.put(property.name(), propertyDescriptor(property)));

        Map<String, MemberDescriptor> statics = new LinkedHashMap<>();
        for (ClassDeclStmt.Factory factory : type.factories()) {
            statics.put(factory.name(), methodDescriptor(factory.name(), factory.function()));
        }
        return new SymbolDescriptor(symbol.name(), SymbolKind.CLASS, symbol.signature(),
                symbol.documentation(), Origin.FILE, List.copyOf(instance.values()),
                List.copyOf(statics.values()));
    }

    private SymbolDescriptor traitDescriptor(Symbol symbol) {
        if (!(symbol.declaration() instanceof TraitDeclStmt type)) {
            return new SymbolDescriptor(symbol.name(), SymbolKind.TRAIT, symbol.signature(),
                    symbol.documentation(), Origin.FILE);
        }
        Map<String, MemberDescriptor> members = new LinkedHashMap<>();
        for (FunctionExpr.Param field : type.params()) {
            members.put(field.name(), MemberDescriptor.property(field.name(), false, null));
        }
        type.methods().forEach(method -> members.put(method.memberName(), methodDescriptor(method)));
        type.requirements().forEach(requirement -> members.put(requirement.name(),
                MemberDescriptor.method(requirement.name(), arityOf(requirement.params(),
                        requirement.variadic()), requirement.name() + "(…)", null)));
        type.properties().forEach(property -> members.put(property.name(), propertyDescriptor(property)));
        return new SymbolDescriptor(symbol.name(), SymbolKind.TRAIT, symbol.signature(),
                symbol.documentation(), Origin.FILE, List.copyOf(members.values()));
    }

    private MemberDescriptor methodDescriptor(FunctionExpr function) {
        return methodDescriptor(function.memberName(), function);
    }

    private MemberDescriptor methodDescriptor(String name, FunctionExpr function) {
        return MemberDescriptor.method(name, arityOf(function.params(), function.rest() != null),
                name + parameters(function),
                documentationOf(function));
    }

    private MemberDescriptor propertyDescriptor(PropertyDecl property) {
        return MemberDescriptor.property(property.name(), false, documentationOf(property));
    }

    private String documentationOf(Node declaration) {
        for (Symbol symbol : analysis.symbols()) {
            if (symbol.declaration() == declaration) {
                return symbol.documentation();
            }
        }
        return null;
    }

    private static Arity arityOf(List<FunctionExpr.Param> parameters, boolean variadic) {
        int required = 0;
        for (FunctionExpr.Param parameter : parameters) {
            if (parameter.defaultValue() == null) {
                required++;
            }
        }
        return variadic ? Arity.atLeast(required) : Arity.between(required, parameters.size());
    }

    private static String parameters(FunctionExpr function) {
        StringBuilder result = new StringBuilder("(");
        for (int index = 0; index < function.params().size(); index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(function.params().get(index));
        }
        if (function.rest() != null) {
            if (!function.params().isEmpty()) {
                result.append(", ");
            }
            result.append('*').append(function.rest().name());
        }
        if (function.namedRest() != null) {
            if (!function.params().isEmpty() || function.rest() != null) {
                result.append(", ");
            }
            result.append("**").append(function.namedRest().name());
        }
        return result.append(')').toString();
    }

    private static Origin originOf(ReceiverType receiver) {
        return switch (receiver) {
            case ReceiverType.Class type -> type.descriptor().origin();
            case ReceiverType.Trait trait -> trait.descriptor().origin();
            case ReceiverType.Builtin ignored -> Origin.BUILTIN;
            case ReceiverType.Module ignored -> Origin.MODULE;
            default -> Origin.FILE;
        };
    }

    /** Описание выбранного члена: оно может быть именем модуля или простым членом. */
    public sealed interface ResolvedMember permits ResolvedMember.Name, ResolvedMember.Member {

        static Name name(SymbolDescriptor descriptor) {
            return new Name(descriptor);
        }

        static Member member(MemberDescriptor descriptor, Origin origin) {
            return new Member(descriptor, origin);
        }

        record Name(SymbolDescriptor descriptor) implements ResolvedMember {
        }

        record Member(MemberDescriptor descriptor, Origin origin) implements ResolvedMember {
        }
    }
}
