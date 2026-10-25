// @vitest-environment jsdom
import {afterEach,describe,expect,it,vi} from 'vitest';
import {createApp,h,nextTick,ref,type App} from 'vue';
import ResourceVersionConfigEditor from './ResourceVersionConfigEditor.vue';
import {api} from '../services/api';
import type {ResourceType,ResourceVersionConfiguration} from '../types';
vi.mock('../services/api',()=>({api:{listToolConnectorDefinitions:vi.fn(),listIntegrationAccounts:vi.fn()}}));
let app:App;let container:HTMLDivElement;
afterEach(()=>{app?.unmount();container?.remove();vi.restoreAllMocks();});
describe('independent model setup',()=>{
 it('loads connector services only when a Tool configuration is selected',async()=>{
  window.matchMedia=vi.fn().mockReturnValue({matches:false,addListener:vi.fn(),removeListener:vi.fn(),addEventListener:vi.fn(),removeEventListener:vi.fn()});
  vi.mocked(api.listToolConnectorDefinitions).mockResolvedValue([]);vi.mocked(api.listIntegrationAccounts).mockResolvedValue([]);
  const type=ref<ResourceType>('LLM_MODEL');const configuration=ref<ResourceVersionConfiguration>({type:'LLM_MODEL'});
  container=document.createElement('div');document.body.append(container);app=createApp({setup:()=>()=>h(ResourceVersionConfigEditor,{resourceType:type.value,configuration:configuration.value})});app.mount(container);await nextTick();
  expect(api.listToolConnectorDefinitions).not.toHaveBeenCalled();expect(api.listIntegrationAccounts).not.toHaveBeenCalled();
  type.value='TOOL';configuration.value={type:'TOOL'};await nextTick();
  await vi.waitFor(()=>expect(api.listToolConnectorDefinitions).toHaveBeenCalledTimes(1));expect(api.listIntegrationAccounts).toHaveBeenCalledTimes(1);
 });
});
