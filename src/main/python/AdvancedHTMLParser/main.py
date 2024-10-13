import random
import string
import time
from AdvancedHTMLParser import AdvancedHTMLParser

MAX_LENGTH = 1000
SEED = 1234
FUZZ_TIME_LIMIT = 10 

random.seed(SEED)
start_time = time.time()

def random_string(length: int) -> str:
    return ''.join(random.choice(string.ascii_lowercase) for _ in range(random.randint(1, length)))

def random_html_tag(depth: int) -> str:
    if depth == 0:
        return random_string(50)

    tag = random.choice(["div", "span", "p", "a", "h1", "h2", "ul", "li", "ol"])
    html = f"<{tag}"

    # Добавление атрибута с 50% вероятностью
    if random.choice([True, False]):
        attr_name = random.choice(["class", "id", "style", "title"])
        attr_value = random_string(20)
        html += f' {attr_name}="{attr_value}"'  
    
    html += ">"  

    # Добавление вложенных тегов или текста
    if random.choice([True, False]):
        html += random_html_tag(depth - 1)
    else:
        html += random_string(50)

    html += f"</{tag}>" 
    return html

def mutate_string(original: str) -> str:
    mutation_operations = [
        remove_random_char,
        add_random_char,
        swap_random_chars
    ]
    mutation = random.choice(mutation_operations)
    return mutation(original)

def remove_random_char(s: str) -> str:
    if not s:
        return s
    index = random.randint(0, len(s) - 1)
    return s[:index] + s[index + 1:]

def add_random_char(s: str) -> str:
    index = random.randint(0, len(s))
    new_char = chr(random.randint(32, 126))  
    return s[:index] + new_char + s[index:]

def swap_random_chars(s: str) -> str:
    if len(s) < 2:
        return s
    idx1, idx2 = random.sample(range(len(s)), 2)
    lst = list(s)
    lst[idx1], lst[idx2] = lst[idx2], lst[idx1]
    return ''.join(lst)

# Фаззинг HTML
def run_fuzzer(grammar_based: bool, mutation_based: bool):
    print(f"Starting fuzzing with seed: {SEED}")
    error_count = 0
    error_messages = []
    parser = AdvancedHTMLParser()

    while time.time() - start_time <= FUZZ_TIME_LIMIT:
        html_input = random_html_tag(depth=random.randint(1, 3))
        if mutation_based and random.random() < 0.5:  # 50% шанс на мутацию
            html_input = mutate_string(html_input)

        # Проверка перед парсингом
        if not is_valid_html(html_input):
            continue 

        try:
            parser.parseStr(html_input)
        except Exception as err:
            error_log = f"Error: {err} for input: {html_input}"
            print(error_log)
            error_messages.append(error_log)
            error_count += 1

    print(f"Total errors found: {error_count}")

    # Сохранение ошибок в файл
    with open("fuzzer_errors.log", "w") as log_file:
        for message in error_messages:
            log_file.write(message + "\n")

# Проверка валидности HTML
def is_valid_html(html: str) -> bool:
    # Простейшая проверка: теги должны быть правильно открыты и закрыты
    tags = ["div", "span", "p", "a", "h1", "h2", "ul", "li", "ol"]
    stack = []

    for tag in tags:
        open_tag = f"<{tag}>"
        close_tag = f"</{tag}>"
        if open_tag in html:
            stack.append(tag)
        if close_tag in html:
            if not stack or stack[-1] != tag:
                return False
            stack.pop()

    return not stack 


run_fuzzer(grammar_based=True, mutation_based=True)