const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

async function check(additions, deletions, files = []) {
  const workflow = readFileSync('.github/workflows/pr-size.yml', 'utf8');
  const script = workflow.split('          script: |\n')[1];
  assert.ok(script, 'workflow must contain its GitHub API size check');
  const messages = [];
  const failures = [];
  const github = {
    rest: { pulls: {
      get: async () => ({ data: { additions, deletions, head: { sha: 'head' } } }),
      listFiles: 'files endpoint',
    } },
    paginate: async (endpoint, parameters) => {
      assert.equal(endpoint, 'files endpoint');
      assert.equal(parameters.pull_number, 207);
      return files;
    },
  };
  const context = {
    repo: { owner: 'techtaurant', repo: 'be' },
    issue: { number: 207 },
    payload: { pull_request: { head: { sha: 'head' } } },
  };
  const core = { info: value => messages.push(value), setFailed: value => failures.push(value) };
  await new AsyncFunction('github', 'context', 'core', script)(github, context, core);
  return { messages, failures, workflow };
}

test('500 added and deleted lines pass, 501 fail', async () => {
  assert.deepEqual((await check(300, 200)).failures, []);
  assert.match((await check(300, 201)).failures[0], /501.*500/);
});

test('renamed and generated files use the complete PR totals', async () => {
  const result = await check(499, 2, [
    { filename: 'generated/Tables.kt', status: 'renamed', patch: '@@' },
  ]);
  assert.equal(result.failures.length, 1);
  assert.match(result.messages.join('\n'), /additions=499 deletions=2 total=501/);
});

test('binary or unavailable patches are reported without excluding their change', async () => {
  const result = await check(1, 0, [{ filename: 'image.png', status: 'added' }]);
  assert.match(result.messages.join('\n'), /image.png/);
  assert.deepEqual(result.failures, []);
});

test('workflow requests only PR read permission and never checks out PR code', async () => {
  const { workflow } = await check(0, 0);
  assert.match(workflow, /pull-requests: read/);
  assert.doesNotMatch(workflow, /actions\/checkout|pull_request_target|contents: write/);
});
