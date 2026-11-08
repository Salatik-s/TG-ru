const repos = ['exteraless/exteraless', 'risin42/NagramX', 'ferelking242/novagramx'];
await Promise.all(repos.map(async repo => {
  try {
    const data = await (await fetch(`https://api.github.com/repos/${repo}/releases/latest`)).json();
    console.log(JSON.stringify({repo, tag:data.tag_name, commit:data.target_commitish, assets:data.assets?.map(a=>({name:a.name,size:a.size,url:a.browser_download_url,digest:a.digest}))}));
  } catch (error) { console.log(repo, String(error)); }
}));
