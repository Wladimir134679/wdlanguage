// безопасное обращение, оборванное на середине: так выглядит строка,
// пока её ещё набирают
config = load()
host = config?.
port = config?.[
first = config?.db?.
fallback = config?.db?.host ??
