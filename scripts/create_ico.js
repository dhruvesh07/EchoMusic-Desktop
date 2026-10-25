const { execSync } = require('child_process');
const fs = require('fs');

const files = [
  'assets/branding/icon_256.png',
  'assets/branding/icon_128.png',
  'assets/branding/icon_64.png',
  'assets/branding/icon_48.png',
  'assets/branding/icon_32.png',
  'assets/branding/icon_16.png'
].join(' ');

console.log('Generating ICO with npx png-to-ico...');
const buf = execSync(`npx -y png-to-ico ${files}`, { maxBuffer: 10 * 1024 * 1024 });

fs.writeFileSync('assets/branding/icon.ico', buf);
fs.writeFileSync('desktop/src/main/resources/icon.ico', buf);
console.log('Successfully written icon.ico (' + buf.length + ' bytes)!');
