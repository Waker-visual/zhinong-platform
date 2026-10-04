import { createApp } from "vue";
import App from "./App.vue";
import { validate } from "./ui/validate";
import "./fonts.css";
import "./style.css";

import "./account/theme.css";
import "./account/appearance";
import "./ui/touch.css";

createApp(App).directive("validate", validate).mount("#app");
