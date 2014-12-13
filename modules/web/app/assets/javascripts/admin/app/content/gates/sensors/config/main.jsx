/*
 * Copyright 2014 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define(['react', 'core_mixin', 'common_panel_startstop', 'common_panel_delete', './ConfigInfoPanel'],
    function (React, core_mixin, StartStopPanel, DeletePanel, ConfigInfoPanel) {

        return React.createClass({
            mixins: [core_mixin],

            componentName: function () {
                return "app/content/gates/sensors/config/main/" + this.props.ckey;
            },


            renderData: function () {
                var self = this;

                return (
                    <div className="row withspace">

                        <div className="col-md-8">
                            <ConfigInfoPanel  {...self.props} />
                        </div>

                        <div className="col-md-2">
                            <StartStopPanel {...self.props} subject="sensor"/>
                        </div>
                        <div className="col-md-2">
                            <DeletePanel {...self.props} subject="sensor"/>
                        </div>

                    </div>
                );

            },

            render: function () {
                return this.renderData();
            }
        });

    });