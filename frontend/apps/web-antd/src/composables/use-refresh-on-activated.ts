import { onActivated } from 'vue';

/** Keep the normal first-load query; refresh cached pages only when returning. */
export function useRefreshOnActivated(refresh: () => unknown) {
  let activated = false;
  onActivated(() => {
    if (!activated) {
      activated = true;
      return;
    }
    return refresh();
  });
}
