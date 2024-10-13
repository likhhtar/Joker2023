import random
import string
from html5lib import HTMLParser

# Функция для генерации случайной строки
def generate_random_string(length=10):
    return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

# Генерация HTML-кода с вложенностью
def generate_nested_html(depth=0):
    if depth > 3:  # Максимальная глубина вложенности
        return generate_random_string()
    
    tag = random.choice(['div', 'span', 'p', 'a', 'b', 'i'])
    inner_html = generate_nested_html(depth + 1)  # Рекурсивный вызов для вложенности
    return f"<{tag}>{inner_html}</{tag}>"

# Генерация HTML-кода с атрибутами
def generate_html_with_attributes():
    tags = ['div', 'span', 'p', 'a', 'b', 'i']
    html = '<!DOCTYPE html><html><body>'
    
    for _ in range(random.randint(1, 5)):
        tag = random.choice(tags)
        # Добавление некорректного атрибута
        if random.random() < 0.5:
            attribute = f"unknownAttr='{generate_random_string()}'"
            html += f"<{tag} {attribute}>{generate_random_string()}</{tag}>"
        else:
            html += f"<{tag}>{generate_random_string()}</{tag}>"
    
    html += '</body></html>'
    return html

# Генерация недостающих закрывающих тегов
def generate_incomplete_html():
    tags = ['div', 'span', 'p', 'a', 'b', 'i']
    html = '<!DOCTYPE html><html><body>'
    
    for _ in range(random.randint(1, 5)):
        tag = random.choice(tags)
        html += f"<{tag}>{generate_random_string()}"
        if random.random() < 0.5:  # 50% шанс не закрыть тег
            continue  # Пропустим закрывающий тег

        html += f"</{tag}>"
    
    html += '</body></html>'
    return html

# Генерация HTML-кода со специальными символами
def generate_html_with_special_characters():
    special_chars = ['<', '>', '&', '"', "'", '\x00', '\x01']
    html = '<!DOCTYPE html><html><body>'
    
    for _ in range(random.randint(1, 5)):
        tag = random.choice(['div', 'span', 'p'])
        content = ''.join(random.choices(string.ascii_letters + random.choice(special_chars), k=10))
        html += f"<{tag}>{content}</{tag}>"
    
    html += '</body></html>'
    return html

def generate_fuzzed_html():
    generation_methods = [
        generate_nested_html,
        generate_html_with_attributes,
        generate_incomplete_html,
        generate_html_with_special_characters
    ]
    
    method = random.choice(generation_methods)
    return method()

def fuzz_html5lib(parse_func, iterations=100):
    for _ in range(iterations):
        random_html = generate_fuzzed_html()
        try:
            parse_func(random_html)
        except Exception as e:
            print(f"Unexpected error: {e} for input: {random_html}")

if __name__ == "__main__":
    parser = HTMLParser(strict=False)
    fuzz_html5lib(parser.parse)