<script lang="ts">
import { defineComponent, inject, onMounted, onUnmounted, onUpdated } from 'vue';
import { pageHeadActionsKey } from '../composables/pageHeadActions';

export default defineComponent({
  name: 'PageHeadActions',
  setup(_, { slots }) {
    const registry = inject(pageHeadActionsKey, null);
    const owner = Symbol('page-head-actions');

    function register() {
      if (!registry) {
        return;
      }
      registry.value = {
        owner,
        render: () => slots.default?.() ?? [],
      };
    }

    onMounted(register);
    onUpdated(register);
    onUnmounted(() => {
      if (!registry || registry.value?.owner !== owner) {
        return;
      }
      registry.value = null;
    });

    return () => null;
  },
});
</script>
