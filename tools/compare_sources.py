"""오프라인 카메라 소스 비교. 외부 코드를 프로젝트 소스로 복사하지 않는다.

실행: python tools/compare_sources.py --workspace <프로젝트> --comparators <비교 저장소 폴더>
기본 결과는 프로젝트의 output/source-comparison.json에 저장된다.
--output으로 경로를 선택하며, 기존 결과를 덮어쓰려면 --overwrite를 명시한다.
"""
import argparse
import bisect
from collections import defaultdict
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import time
import tokenize

EXTENSIONS = {'.kt', '.java', '.cpp', '.cc', '.cxx', '.c', '.h', '.hpp', '.glsl', '.comp', '.frag', '.vert', '.py'}
LOCAL_ROOTS = ['app', 'camera-camera2', 'domain', 'feature-camera', 'feature-diagnostics',
               'native', 'processing-api', 'processing-native', 'experiments']
SKIP = {'.git', '.gradle', '.idea', '.kotlin', '.cxx', 'build', 'output', 'node_modules', '__pycache__'}
TEST_PARTS = {'test', 'tests', 'androidTest', 'testFixtures', 'unittest', 'unittests'}
KEYWORDS = set(('package import static public private protected internal class object interface enum '
                'data fun val var const if else when for while do return break continue try catch finally '
                'throw throws new this super null true false void int long float double bool boolean '
                'char byte short unsigned signed auto struct typedef namespace using template typename '
                'virtual override final abstract volatile synchronized suspend inline open lateinit '
                'in out inout uniform buffer layout readonly writeonly shared precision highp mediump '
                'lowp def from as with lambda pass yield raise except None True False and or not is '
                'sizeof switch case default delete operator extern constexpr nullptr').split())
LEXER = re.compile(r'(?P<comment>//[^\n]*|/\*[\s\S]*?\*/)|'
                   r'(?P<string>"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\')|'
                   r'(?P<identifier>[A-Za-z_$][A-Za-z_0-9$]*)|'
                   r'(?P<number>(?:0[xX][0-9a-fA-F]+|\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)[uUlLfF]*)|'
                   r'(?P<operator>[^\s])')
TRIPLES = re.compile(r'"""([\s\S]*?)"""')
MASK = (1 << 64) - 1
BASE = 1000003


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def entries(root, upstream=False):
    selected = []
    for path in root.rglob('*'):
        if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
            continue
        relative = path.relative_to(root)
        if any(part in SKIP for part in relative.parts):
            continue
        if upstream and (any(part in TEST_PARTS for part in relative.parts) or
                         path.name.lower().startswith('test_')):
            continue
        selected.append(path)
    return sorted(selected)


def lex(source, suffix):
    tokens, alpha, lines = [], [], []
    if suffix == '.py':
        ignored = {tokenize.COMMENT, tokenize.ENCODING, tokenize.ENDMARKER,
                   tokenize.NL, tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT}
        for item in tokenize.generate_tokens(io.StringIO(source).readline):
            if item.type in ignored:
                continue
            token = '<STRING>' if item.type == tokenize.STRING else item.string
            tokens.append(token)
            alpha.append('<ID>' if item.type == tokenize.NAME and token not in KEYWORDS else token)
            lines.append(item.start[0])
        return tokens, alpha, lines
    newlines = [-1] + [m.start() for m in re.finditer('\n', source)]
    for item in LEXER.finditer(source):
        kind = item.lastgroup
        if kind == 'comment':
            continue
        token = '<STRING>' if kind == 'string' else item.group()
        tokens.append(token)
        alpha.append('<ID>' if kind == 'identifier' and token not in KEYWORDS else token)
        lines.append(bisect.bisect_left(newlines, item.start()))
    return tokens, alpha, lines


def load_files(paths, root, repository):
    files, units = [], []
    for path in paths:
        raw = path.read_bytes()
        relative = path.relative_to(root).as_posix()
        source = raw.decode('utf-8', errors='replace')
        record = {'repository': repository, 'path': relative, 'sha256': hashlib.sha256(raw).hexdigest(),
                  'bytes': len(raw), 'lines': len(source.splitlines()),
                  'role': '검사' if any(p in TEST_PARTS for p in Path(relative).parts) or path.name.startswith('test_') else '운영·도구'}
        files.append(record)
        tokens, alpha, lines = lex(source, path.suffix.lower())
        import_lines = {i for i, line in enumerate(source.splitlines(), 1)
                        if re.match(r'^\s*(?:package|import)\s|^\s*#\s*include\b', line)}
        kept = [i for i, line in enumerate(lines) if line not in import_lines]
        units.append(dict(record, unit=relative, tokens=tokens, alpha=alpha, tokenLines=lines,
                          codeTokens=[tokens[i] for i in kept], codeAlpha=[alpha[i] for i in kept],
                          codeLines=[lines[i] for i in kept]))
        if path.suffix.lower() in {'.kt', '.java', '.cpp', '.h'}:
            for index, match in enumerate(TRIPLES.finditer(source)):
                if '#version' not in match.group(1) and 'void main' not in match.group(1):
                    continue
                shader_tokens, shader_alpha, shader_lines = lex(match.group(1), '.glsl')
                line_offset = source[:match.start(1)].count('\n')
                units.append(dict(record, unit=f'{relative}#shader-{index + 1}', tokens=shader_tokens,
                                  alpha=shader_alpha, tokenLines=[n + line_offset for n in shader_lines],
                                  codeTokens=shader_tokens, codeAlpha=shader_alpha,
                                  codeLines=[n + line_offset for n in shader_lines]))
    return files, units


def shingle_hashes(ids, size):
    if len(ids) < size:
        return
    power = pow(BASE, size - 1, 1 << 64)
    value = 0
    for token in ids[:size]:
        value = (value * BASE + token) & MASK
    yield 0, value
    for index in range(size, len(ids)):
        value = (((value - ids[index - size] * power) & MASK) * BASE + ids[index]) & MASK
        yield index - size + 1, value


def compare(local, upstream, mode, size):
    line_key = 'codeLines' if mode.startswith('code') else 'tokenLines'
    token_ids = {}
    def encode(tokens):
        return [token_ids.setdefault(token, len(token_ids) + 1) for token in tokens]
    local_ids = [encode(unit[mode]) for unit in local]
    index = defaultdict(list)
    for unit_index, ids in enumerate(local_ids):
        for at, hashed in shingle_hashes(ids, size):
            index[hashed].append((unit_index, at))
    # 반복 구간도 생략하지 않고 실제 토큰 동일성까지 확인한다.
    matches = []
    for remote_index, remote in enumerate(upstream):
        remote_ids = encode(remote[mode])
        covered = {}
        for remote_at, hashed in shingle_hashes(remote_ids, size):
            for local_index, local_at in index.get(hashed, ()):
                diagonal = local_at - remote_at
                if covered.get((local_index, diagonal), -1) >= remote_at + size:
                    continue
                ids = local_ids[local_index]
                if ids[local_at:local_at + size] != remote_ids[remote_at:remote_at + size]:
                    continue
                left = 0
                while local_at > left and remote_at > left and ids[local_at-left-1] == remote_ids[remote_at-left-1]:
                    left += 1
                right = size
                while local_at+right < len(ids) and remote_at+right < len(remote_ids) and ids[local_at+right] == remote_ids[remote_at+right]:
                    right += 1
                covered[(local_index, diagonal)] = remote_at + right
                source = local[local_index]
                matches.append({'mode': mode, 'tokenCount': right + left,
                                'localUnit': source['unit'], 'localRole': source['role'],
                                'localStartLine': source[line_key][local_at-left],
                                'localEndLine': source[line_key][local_at+right-1],
                                'repository': remote['repository'], 'remoteUnit': remote['unit'],
                                'remoteStartLine': remote[line_key][remote_at-left],
                                'remoteEndLine': remote[line_key][remote_at+right-1]})
        if (remote_index + 1) % 250 == 0:
            print(f'{mode}: {remote_index + 1}/{len(upstream)} 비교', flush=True)
    return sorted(matches, key=lambda row: (-row['tokenCount'], row['localUnit'], row['repository'], row['remoteUnit']))


def self_test():
    source = 'float develop(float value) {\n' + '\n'.join(
        f'float stage{i} = value * {i + 1}.0 + adjust(value, {i + 2}.0);' for i in range(30)) + '\nreturn stage29; }'
    copied = '// comment and spacing change\n' + source.replace(' = ', '   =   ')
    renamed = re.sub(r'\b(value|develop|adjust|stage\d+)\b', lambda m: 'renamed_' + m.group(), source)
    def unit(text):
        tokens, alpha, lines = lex(text, '.cpp')
        return {'tokens': tokens, 'alpha': alpha, 'tokenLines': lines, 'unit': 'synthetic.cpp',
                'role': '양성 대조', 'repository': 'synthetic'}
    original = unit(source)
    assert compare([original], [unit(copied)], 'tokens', 50)
    assert compare([original], [unit(renamed)], 'alpha', 140)
    assert not compare([original], [unit(renamed)], 'tokens', 50)
    embedded = 'val source = """\n#version 310 es\nvoid main() {}\n"""'
    assert '#version' in next(TRIPLES.finditer(embedded)).group(1)
    return {'commentWhitespaceCloneDetected': True, 'identifierRenamedCloneDetected': True,
            'renamedCloneNotReportedAsExact': True, 'embeddedShaderExtractionChecked': True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--comparators', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='결과 JSON 경로; 기본값은 프로젝트/output/source-comparison.json')
    parser.add_argument('--overwrite', action='store_true', help='기존 결과 파일 덮어쓰기를 허용한다')
    args = parser.parse_args()
    destination = args.output if args.output is not None else args.workspace / 'output/source-comparison.json'
    if destination.exists() and not args.overwrite:
        parser.error('결과 파일이 이미 존재합니다. 다른 --output 경로를 쓰거나 --overwrite를 명시하세요.')
    started = time.time()
    controls = self_test()
    local_paths = [path for name in LOCAL_ROOTS for path in entries(args.workspace / name)]
    local_files, local_units = load_files(local_paths, args.workspace, 'LUNA PHOTON')
    repositories, remote_files, remote_units = [], [], []
    for root in sorted(args.comparators.iterdir()):
        if not root.is_dir() or not (root / '.git').exists():
            continue
        paths = entries(root, upstream=True)
        files, units = load_files(paths, root, root.name)
        repositories.append({'name': root.name, 'url': git(root, 'config', '--get', 'remote.origin.url'),
                             'commit': git(root, 'rev-parse', 'HEAD'), 'sourceFileCount': len(files),
                             'comparisonUnitCount': len(units), 'tokens': sum(len(u['tokens']) for u in units)})
        remote_files.extend(files)
        remote_units.extend(units)
    digest_index = defaultdict(list)
    for file in remote_files:
        digest_index[file['sha256']].append(file)
    identical = [{'local': file, 'external': other} for file in local_files for other in digest_index.get(file['sha256'], ())]
    print(f'로컬 {len(local_files)} 파일/{len(local_units)} 단위; 외부 {len(remote_files)} 파일/{len(remote_units)} 단위', flush=True)
    exact = compare(local_units, remote_units, 'tokens', 50)
    normalized = compare(local_units, remote_units, 'alpha', 140)
    code_exact = compare(local_units, remote_units, 'codeTokens', 40)
    code_normalized = compare(local_units, remote_units, 'codeAlpha', 100)
    report = {'schemaVersion': 1, 'method': '오프라인 SHA-256 및 연속 토큰 비교. 주석·공백 제거, 문자열 내용은 STRING 토큰; 별도 내장 GLSL 추출. 식별자 정규화 비교는 키워드를 남기고 다른 식별자를 ID로 대체한다.',
              'scriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), 'positiveControls': controls,
              'thresholds': {'exactTokens': 50, 'identifierNormalizedTokens': 140,
                             'withoutImportPackageIncludeExact': 40, 'withoutImportPackageIncludeNormalized': 100,
                             'hashBits': 64, 'hashEqualityVerified': True, 'occurrenceCap': None},
              'scope': {'localRoots': LOCAL_ROOTS, 'extensions': sorted(EXTENSIONS), 'excludedDirectories': sorted(SKIP), 'externalTestsExcluded': sorted(TEST_PARTS),
                        'localFileCount': len(local_files), 'localUnitCount': len(local_units), 'externalFileCount': len(remote_files), 'externalUnitCount': len(remote_units)},
              'repositories': repositories, 'localFiles': local_files, 'externalFiles': remote_files,
              'identicalFiles': identical, 'exactTokenMatches': exact, 'normalizedTokenMatches': normalized,
              'codeExactMatches': code_exact, 'codeNormalizedMatches': code_normalized,
              'limitations': ['짧은 복사, 큰 구조 변경, 다른 언어 재작성, 문자열 내부 변경, 주석 복사 및 비교 집합 밖 출처는 검출하지 못할 수 있다.',
                              '일치는 저작권 침해의 증명이 아니며, 일치 없음도 독창성·무단 복제 없음의 증명이 아니다.',
                              'MotionCam, 비공개·상용 앱 소스, 비교 저장소의 다른 커밋은 검사하지 않았다.',
                              '검토 범위에서 추출 가능한 Kotlin/Java/C++ 삼중 따옴표 GLSL을 별도 포함한다. 다른 동적 문자열 조립은 완전하게 추출하지 않는다.'],
              'elapsedSeconds': round(time.time() - started, 3)}
    destination.parent.mkdir(parents=True, exist_ok=True)
    # 비교 중 다른 프로세스가 결과를 생성해도 명시적 허용 없이 덮어쓰지 않는다.
    with destination.open('w' if args.overwrite else 'x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'identicalFiles': len(identical), 'exactMatches': len(exact), 'normalizedMatches': len(normalized),
                      'codeExactMatches': len(code_exact), 'codeNormalizedMatches': len(code_normalized),
                      'report': str(destination)}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
