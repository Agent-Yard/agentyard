// @vitest-environment jsdom
import {afterEach,describe,expect,it} from 'vitest';
import {createApp,type App} from 'vue';
import ContextContent from './ContextContent.vue';
let app:App|undefined;
let container:HTMLDivElement|undefined;
afterEach(()=>{app?.unmount();container?.remove();});
function render(body:Record<string,unknown>){container=document.createElement('div');document.body.append(container);app=createApp(ContextContent,{body});app.mount(container);return container;}
describe('shared work content',()=>{
 it('renders a real Agent markdown artifact as a readable table and keeps structured fields',()=>{
  const el=render({markdown:'# 交付成果\n\n| 检查 | 标准 |\n| --- | --- |\n| 收银 | 账实一致 |',checked:true});
  expect(el.querySelector('h1')?.textContent).toBe('交付成果');
  expect(el.querySelector('table')?.textContent).toContain('账实一致');
  expect(el.querySelector('details')?.textContent).toContain('"checked": true');
 });
 it('sanitizes contributed content while retaining plain text support',()=>{
  const el=render({text:'**结论** <img src=x onerror="alert(1)"><script>alert(1)</script>'});
  expect(el.querySelector('strong')?.textContent).toBe('结论');
  expect(el.querySelector('script')).toBeNull();
  expect(el.querySelector('img')?.hasAttribute('onerror')).toBe(false);
 });
});
