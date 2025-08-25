const { promisify } = require("util");
const copydir = promisify(require("child_process").exec);
const path = require("path");
const fs = require("fs");

const { get_sha, get_commit_date } = require("./git.js");
const { scrub, sanitize_app_name, ex_clone_path } = require("./lib/sanitize");

async function copy_local_app(app_spec, target) {
  console.log(`[copy_local] processing local app: ${app_spec}`);

  // Parse local path specification - format: "local:/path/to/app" or just "/path/to/app"
  let local_path = app_spec;
  if (app_spec.startsWith("local:")) {
    local_path = app_spec.substring(6); // Remove 'local:' prefix
  }
  if (!path.isAbsolute(local_path)) {
    local_path = path.resolve(process.cwd(), local_path);
  }
  if (!fs.existsSync(local_path)) {
    throw new Error(`Local app path does not exist: ${local_path}`);
  }
  const pkg_path = path.join(local_path, "package.json");
  if (!fs.existsSync(pkg_path)) {
    throw new Error(`package.json not found in local app: ${local_path}`);
  }

  const pkg = require(pkg_path);
  const pkg_name = pkg.name;
  const name = scrub(pkg_name);
  const app_name = ex_clone_path(pkg_name) || name;

  // Create target directory name (consistent with git implementation)
  const web_name = sanitize_app_name(pkg_name);
  const copy_path = path.join(target, web_name);

  const build_app_path = path.join(local_path, "build", "app");
  if (!fs.existsSync(build_app_path)) {
    throw new Error(
      `build/app directory not found in local app: ${local_path}`
    );
  }

  console.log(
    `[copy_local] [${pkg_name}] copying from '${build_app_path}' to '${copy_path}'`
  );

  try {
    await copydir(`cp -r "${build_app_path}" "${copy_path}"`);
    console.log(`[copy_local] [${pkg_name}] copy successful`);
  } catch (err) {
    console.error(`[copy_local] [${pkg_name}] copy failed:`, err);
    throw err;
  }

  let sha = "local-dev";
  let build_date = new Date().toISOString();

  try {
    sha = await get_sha(local_path);
    build_date = await get_commit_date(local_path, sha);
  } catch (err) {
    console.log(
      `[copy_local] [${pkg_name}] not a git repository or no commits, using fallback values`
    );
  }

  return {
    ref: "local",
    sha,
    build_date,
    pkg_name,
    version: pkg.version,
    name,
    web_name,
    url: `local:${local_path}`,
    path: copy_path,
  };
}

function is_local_app(app_spec) {
  return (
    app_spec.startsWith("local:") ||
    app_spec.startsWith("/") ||
    app_spec.startsWith("./") ||
    app_spec.startsWith("../")
  );
}

module.exports = { copy_local_app, is_local_app };
