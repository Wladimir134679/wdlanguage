// Свой двоичный формат: собрать, записать, прочитать, проверить
//
// Задача целиком, а не набор вызовов: небольшой формат файла описан, собран
// буфером, сохранён на диск, разобран курсором обратно — и результат сверен
// с исходными данными. Ровно это и было невозможно, пока язык умел читать
// только текст в UTF-8.
//
// Формат (порядок байтов всюду little-endian):
//   "WDB1"        4 байта — подпись
//   version       uint8
//   count         uint16 — сколько записей
//   записи:       id int32, затем длина имени uint8 и само имя в utf-8

import sys.bytes as bin
import sys.io as io
import sys.streams as streams

MAGIC = bin.hex("57444231")             // "WDB1" — 57 44 42 31
VERSION = 1

people = [
    {id: 1, name: "Аня"},
    {id: 2, name: "Борис"},
    {id: 3, name: "Вера"},
]

// --- сборка ------------------------------------------------------------------

def encode(records) {
    w = bin.writer()
    w.order("le")
    w.put(MAGIC)
    w.putUint8(VERSION)
    w.putUint16(len(records))
    for (person in records) {
        w.putInt32(person.id)
        // Длина в байтах, а не в символах: "Аня" — три буквы и шесть байт,
        // и читателю нужны именно байты.
        name = person.name.bytes
        w.putUint8(name.size)
        w.put(name)
    }
    return w.bytes;
}

// --- разбор ------------------------------------------------------------------

def decode(data) {
    // Подпись сравнивается целиком. Побайтно было бы и длиннее, и коварнее:
    // data[0] == 0x57 работает, а data[0] == 0x89 для подписи PNG — уже нет,
    // потому что байт наружу выходит знаковым.
    if (!data.startsWith(MAGIC)) {
        throw "это не WDB1: файл начинается с " + data[0..3].hex;
    }

    r = bin.reader(data)
    r.order("le")
    r.skip(MAGIC.size)

    version = r.uint8()
    if (version != VERSION) {
        throw 'версия ${version} мне незнакома';
    }

    records = []
    for (i in 1..r.uint16()) {
        id = r.int32()
        records.push({id: id, name: r.text(r.uint8())})
    }
    // Лишние байты в конце — это либо чужой файл, либо ошибка в разборе выше.
    // Молча их проглотить значило бы узнать об этом гораздо позже.
    if (!r.done) {
        throw 'после записей осталось ${r.remaining} байт';
    }
    return records;
}

packet = encode(people)
println("собрано: ", packet, " (", packet.size, " байт)")
println("подпись на месте: ", packet.startsWith(MAGIC))

// --- круг через диск ---------------------------------------------------------

// Путь считается от текущего каталога процесса, а не от каталога скрипта, поэтому
// каталог создаётся здесь же: пример запускают и из корня репозитория, и из своей папки.
path = io.mkdirs("build") + io.SEPARATOR + "people.wdb"
io.writeBytes(path, packet)

loaded = io.readBytes(path)
println("файл совпал байт в байт: ", loaded == packet)     // true

for (person in decode(loaded)) {
    println("  #", person.id, " ", person.name)
}

// Испорченный файл ловится там, где испортился, а не «как-нибудь».
println(try? decode(bin.hex("00000000")))                  // null — нет подписи
println(try? decode(packet[0..6]))                         // null — обрезан

// --- когда файл в память не влезает ------------------------------------------

// io.readBytes читает целиком, и для картинки это правильно. Для пятидесяти
// гигабайт — нет: streams.chunks держит в памяти ровно один кусок.
use (s = streams.chunks(path, 8)) {
    sizes = s.map(c => c.size).list()
    println("кусками по 8: ", sizes, ", всего ", sizes.sum, " байт")
}

// Контрольная сумма тем же способом — ни один кусок не живёт дольше своей итерации.
use (s = streams.chunks(path, 8)) {
    println("сумма байтов: ", s.map(c => c.numbers.sum).sum())
}

io.remove(path)
