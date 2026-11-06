import {createApp,h,nextTick,ref} from 'vue';
import {expect,it,vi} from 'vitest';
import PdfPage from './pdf-page.vue';
it('bounds raster allocation and releases a page when it leaves the working set',async()=>{
 const cleanup=vi.fn();const rendering={promise:Promise.resolve(),cancel:vi.fn()};
 const page={cleanup,getViewport:({scale}:any)=>({width:6000*scale,height:8000*scale}),render:vi.fn(()=>rendering)};
 const pdf={getPage:vi.fn().mockResolvedValue(page)};const active=ref(true);
 const host=document.createElement('div');document.body.append(host);
 const app=createApp({render:()=>h(PdfPage,{document:pdf as any,index:1,active:active.value,width:2000,zoom:2,ratio:1.414})});app.mount(host);
 await new Promise(resolve=>setTimeout(resolve,10));
 const canvas=host.querySelector('canvas')!;expect(canvas).not.toBeNull();expect(canvas.width*canvas.height).toBeLessThan(8_010_000);expect(page.render).toHaveBeenCalledOnce();
 active.value=false;await nextTick();await new Promise(resolve=>setTimeout(resolve,10));
 expect(host.querySelector('canvas')).toBeNull();expect(cleanup).toHaveBeenCalled();app.unmount();host.remove();
});
it('never renders a stale page after closing during an asynchronous page fetch',async()=>{
 let resolvePage:(page:any)=>void=()=>{};const render=vi.fn();
 const pdf={getPage:()=>new Promise(resolve=>{resolvePage=resolve;})};
 const host=document.createElement('div');document.body.append(host);const app=createApp({render:()=>h(PdfPage,{document:pdf as any,index:1,active:true,width:800,zoom:1,ratio:1.414})});app.mount(host);await nextTick();app.unmount();
 resolvePage({render,getViewport:()=>({width:600,height:800})});await nextTick();await nextTick();expect(render).not.toHaveBeenCalled();host.remove();
});
