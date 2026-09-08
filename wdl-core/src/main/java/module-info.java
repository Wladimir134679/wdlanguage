module ru.wds.wdl.core {
    exports ru.wds.wdl.ast;
    exports ru.wds.wdl.ast.expr;
    exports ru.wds.wdl.ast.stmt;
    exports ru.wds.wdl.ast.op;
    exports ru.wds.wdl.ast.visitor;
    exports ru.wds.wdl.diagnostic;
    exports ru.wds.wdl.lexer;
    exports ru.wds.wdl.metrics;
    exports ru.wds.wdl.module;
    exports ru.wds.wdl.parser;
    exports ru.wds.wdl.profile;
    exports ru.wds.wdl.resolve;
    exports ru.wds.wdl.runtime;
    // Наборы членов встроенных типов: по ним анализатор перечисляет 'a.size'
    // и 'text.upper()' — без запуска и без второго списка рядом.
    exports ru.wds.wdl.runtime.members;
    exports ru.wds.wdl.source;
    exports ru.wds.wdl.value;
    exports ru.wds.wdl.value.types;
}
