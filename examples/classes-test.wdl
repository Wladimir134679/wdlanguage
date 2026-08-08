class TestC(val = "DEF") {
    fun toString(){
        return "Тут текст + ваш: " + val;
    }
}

t1 = new TestC("C1")
t2 = new TestC("C2")
test = new TestC(123)

println(t1.toString())
println(t2.toString())
println(test.toString())